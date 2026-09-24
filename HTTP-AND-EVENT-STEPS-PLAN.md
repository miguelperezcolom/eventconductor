# Plan: `HTTP_CALL` and `PUBLISH_EVENT` steps, with payload templates

> Status: **DECISIONS RESOLVED (2026-09-24)** — recommendations accepted; implementation on
> `feat/http-and-event-steps`. One PR per phase;
> each phase compiles and tests green on its own.

## 1. Goal

Stop writing workers whose only job is one REST call or one event emission.

- **`PUBLISH_EVENT`** — publish a domain event (for other services, inboxes, analytics) from the
  process's state. Engine-internal, no worker.
- **`HTTP_CALL`** — call an HTTP endpoint and map the response into process variables. Executed by a
  **built-in worker handler**, shipped with the platform, not written by anyone.
- **Payload templates** for both (and as a third way to build a `REPLY`): JEXL templates applied to
  the process context and variables.

Decided in conversation (2026-09-24), recorded here so the plan can be reviewed against it:

| # | Decision |
|---|---|
| A | Names `HTTP_CALL` and `PUBLISH_EVENT`. |
| B | `HTTP_CALL` runs as a built-in worker task, not inside the engine's step-over. |
| C | Events are CloudEvents by default, with a plain-payload option. |
| D | URLs: a **named connection** *or* an **absolute URL**. Authorization comes from the connection, or — for an absolute URL — from a named **auth profile** or an inline auth block whose secrets are **references only**. |
| E | Templates are JEXL (the engine's sandboxed evaluator), structured (JSON-shaped, typed leaves) or text. |
| F | No secret literal ever in a definition; absolute URLs are subject to a host allowlist that blocks internal addresses by default. |

## 2. What exists to build on

- **Engine-internal steps resolved at start**: `SEND_MESSAGE` emits through the outbox and completes in
  `StepExecution.start()` (`domain/aggregates/StepExecution.java`, SEND_MESSAGE branch); `REPLY` and
  `LOCK/UNLOCK` are resolved in the step-over (`StepOverProcessUseCase.resolveReplySteps` /
  `resolveLockSteps`). `PUBLISH_EVENT` follows `SEND_MESSAGE`.
- **Built-in worker tasks**: `RULE` dispatches `TaskExecutionRequested` with taskId `evaluate-rule`
  and adds `ruleId` to the task variables (`StepExecution.start`, `StartStepExecutionUseCase:73-90`);
  `rule-runtime` serves it (`RuleTaskKafkaConsumerConfig`). `HTTP_CALL` follows the same shape with
  taskId `http-call`.
- **Worker runtime**: `worker-api` (`TaskDispatcher`, `TaskRegistry`, `TaskRegistration(id, version,
  topic, …)`, `TaskFailure`), served by `worker-embedded` (inline in the engine's pod) and
  `worker-kafka`. A built-in handler is just a `TaskRegistration` bean in a new module.
- **Outbox routing**: `RelayDestination` picks the binding per event (`outbox`, `messages`,
  `processIndex`); `OutboxRelay.relay` sends through `PartitionedEvents` (keyed, synchronous).
- **Expression sandbox**: `JEXLEvaluator` (engine) with `ExpressionGuard` and
  `LinearTimeRegexArithmetic` in `modules/shared` — which already depends on `commons-jexl3`, so the
  template engine can live in `shared` and be used by the engine *and* the HTTP worker.
- **Validation**: `WorkflowDefinition.checkInvariants()` + the Maven plugin's `SpecValidator`
  (which already parses JEXL for `correlationExpression`, `replyExpression`, …), schema-first via
  `workflow-definition-schema.json`.
- **Secrets**: nothing in the engine today. Spring's `Environment` already resolves env vars, K8s
  secrets mounted as properties, Vault via Spring Cloud — the natural resolver.

## 3. Design

### 3.1 Templates (`modules/shared`, package `io.mateu.workflow.template`)

`PayloadTemplate.render(Object template, Map<String,Object> context) → Object` and
`TextTemplate.render(String, context) → String`, on the same sandboxed JEXL engine
(RESTRICTED permissions, no loops/lambdas/side effects, size/nesting guard).

**Structured templates** — the payload written as YAML/JSON in the definition:
```yaml
body:
  orderId: "${orderId}"
  total: "${amount * 1.21}"
  customer: { id: "${customerId}", tier: "${tier ?: 'standard'}" }
  items: "${items}"
  note: "Order ${orderId} for ${customerName}"
```
- Objects and arrays are walked; non-string leaves pass through.
- A string leaf that is **exactly one** `${…}` keeps the expression's **type** (number, boolean, list,
  map). A variable whose text is JSON is parsed when used as a whole leaf (`items` above), since
  process variables are strings.
- A string leaf with text around the expressions is a **string** (interpolated).
- `$${` escapes a literal `${`.

**Text templates** — `bodyTemplate: "<order id=\"${orderId}\"/>"` for XML, form-encoded or anything
not JSON; interpolation only, the author owns the format.

**Context** (the same for every template in a step): each process variable by name; `process`
(`id`, `businessKey`, `workflowDefinitionId`, `version`); `step` (`id`, `name`,
`executionId`); `now` (ISO instant); `businessKey`. Nothing else — in particular no environment,
no secrets.

**Validation** (engine on import, Maven plugin at build, one shared class): every `${…}` is parsed;
a template that does not parse is an error. Undefined variables are not (they are runtime data).

**Errors at runtime**: a template that cannot be rendered fails the step (`ERROR`), like a REPLY whose
expression fails — never a silently empty payload.

`REPLY` gains `replyTemplate` as a third, mutually exclusive, way to build its payload.

### 3.2 `PUBLISH_EVENT` — engine-internal

```yaml
- id: announce
  type: PUBLISH_EVENT
  name: Booking confirmed
  destination: bookings          # a logical name, mapped to a topic by configuration
  eventType: com.acme.booking.confirmed
  key: "${bookingId}"            # optional; default the business key, else the process id
  payload:                       # structured template (or payloadTemplate: text, or payloadVariables: [...])
    bookingId: "${bookingId}"
    total: "${total}"
  format: cloudevents            # default; or "plain"
  preconditionStepId: confirm
```

**Execution.** Like `SEND_MESSAGE`: `start()` renders the payload and key, emits an
`ExternalEventRequested(destination, eventType, key, contentType, data, eventId, processId, …)`
domain event into the **outbox, in the same transaction as the step's completion**, and completes the
step. So the event is published **if and only if** the step completed — the same no-loss, no-ghost
guarantee as every transition — and at-least-once on the wire (consumers dedupe by the event `id`).

**Destinations are configuration, never topics in the definition:**
```yaml
workflow.events.destinations:
  bookings: { topic: booking-events }
  audit:    { topic: audit-events, format: plain }
```
An unknown destination fails validation on import (when the destination list is known) and fails
the step at runtime. *Rejected:* a raw `topic` in the definition — a definition imported from git
could otherwise write into `upstream` or `outbox` and forge engine traffic.

**Delivery by mode:**
- **kafka**: `RelayDestination` routes `ExternalEventRequested` to the destination's topic;
  `OutboxRelay` sends it keyed by `key`, synchronously, exactly like every other relayed event (so a
  broker outage parks it `Pending`, and the fast path never claims it — it leaves the shard, see
  `InlineDrive.claimFor`).
- **embedded**: a new port `ExternalEventPublisher`; the default publishes a Spring
  `ApplicationEvent` (`ExternalEventPublished`) in-process; an application that has a broker provides
  its own bean (Kafka, RabbitMQ, SNS…). The handler runs off the outbox like every other event, so the
  guarantee is the same.

**Format.** CloudEvents 1.0, Kafka **binary content mode** (`ce_*` headers, `data` as the record value)
by default — what Kafka consumers of CloudEvents expect — with `structured` as an option:
`id` = the step execution id (stable across relay retries and redeliveries), `source` =
`eventconductor/<definitionId>`, `type` = `eventType`, `subject` = the business key, `time`,
`datacontenttype` = `application/json`, plus extensions `processid` and `stepid`. `format: plain`
sends the payload alone. Trace context travels as `traceparent` (the outbox already carries it).

**Size**: capped by `workflow.events.max-payload-bytes` (default 256 KB), checked at render.

### 3.3 `HTTP_CALL` — a built-in worker task

```yaml
- id: charge
  type: HTTP_CALL
  name: Charge the card
  connection: payments           # EITHER a named connection ...
  method: POST
  path: "/charges"               # ... with a path (template), relative to its base URL
  # url: "https://api.partner.com/orders/${orderId}"   # OR an absolute URL (template)
  # auth: partner-key                                  # ... authorized by a named profile, or inline (§3.4)
  query: { currency: "${currency}" }
  headers: { X-Tenant: "${tenant}" }
  body:                          # structured template (or bodyTemplate: text)
    amount: "${amount}"
    reference: "${bookingId}"
  successStatus: [200, 201]      # default: any 2xx
  output:                        # response → process variables, JEXL over {status, headers, body}
    chargeId: "body.id"
    chargeStatus: "body.status"
  timeout: PT10S
  retries: 2
  compensable: true
  compensationStepId: refund
```

**Where it runs.** `StepExecution.start()` renders everything that is a template (URL/path, query,
headers, body) **in the engine**, with the one template implementation that validation also uses,
and dispatches `TaskExecutionRequested` with taskId `http-call` and the rendered request as a task
variable (`__http`: method, connection or url, auth reference, headers, body, success statuses,
output mapping). No secrets are in it — only the *names* of the connection and auth profile. The step
then behaves like any worker step: timeout, retries with backoff, compensation, cancellation, all
unchanged.

**The handler** — new module `modules/worker-http` (a `TaskRegistration` for `http-call@1`):
- builds the request (JDK `HttpClient`; HTTP/1.1 + 2, connection pooling per connection);
- resolves the base URL and auth from configuration (§3.4), secrets from the Spring `Environment`;
- adds `traceparent` (the process's trace continues into the called service), and an
  **`Idempotency-Key` = the step execution id** — the same across retries of one step execution, so
  an at-least-once redelivery or an engine retry does not charge a card twice on a server that honours
  the header (configurable per connection: header name, or off);
- maps the response: status in `successStatus` → `COMPLETED` with the `output` expressions evaluated
  over `{status, headers, body}` (`body` parsed when JSON); otherwise a `TaskFailure` with code
  `HTTP_<status>` and the first KB of the body as the reason → the step fails and the engine's
  `retries` apply. Connection errors and timeouts fail the same way (code `HTTP_IO`);
- enforces a response size cap (`workflow.http.max-response-bytes`, 1 MB) and the per-connection
  timeouts.

It is served wherever `worker-http` is on the classpath: in **embedded** mode inside the engine's pod
through `worker-embedded` (so the synchronous fast path stays inline through an `HTTP_CALL`); in
**kafka** mode by any worker deployment that includes it — `apps/worker-standalone-app` will, by
default on topic `downstream`, overridable with `workflow.http.topic` (and per step with `topic`).

*Rejected:* executing the call inside the engine. Network I/O inside the step-over holds the process
lock (and, on the fast path, a request thread) for as long as a third party takes, bypasses the
worker machinery that already gives timeouts, retries and cancellation, and in kafka mode puts
egress on orchestrator pods that may not be allowed to have any.

### 3.4 Connections, auth profiles, secrets, and the host allowlist

```yaml
workflow.http:
  connections:
    payments:
      base-url: https://payments.internal
      auth: payments-oauth
      connect-timeout: PT2S
      read-timeout: PT10S
      headers: { X-Client: eventconductor }
      idempotency-header: Idempotency-Key     # or "none"
  auth:
    payments-oauth: { type: oauth2-client-credentials, registration: payments }  # spring.security.oauth2.client.registration.payments
    partner-key:    { type: api-key, header: X-Api-Key, value: "${PARTNER_API_KEY}" }
    legacy:         { type: basic, username: svc, password: "${LEGACY_PASSWORD}" }
    static-bearer:  { type: bearer, token: "${SOME_TOKEN}" }
  allowed-hosts: [ "*.partner.com", "api.stripe.com" ]   # absolute URLs only
```

- **Connection** = base URL + default auth + timeouts + common headers. A step with `connection` may
  still name a different `auth` profile.
- **Absolute URL** = the step says `url`, and `auth` names a profile — or, **inline**:
  `auth: { type: bearer, token: "${secret:PARTNER_TOKEN}" }`. In a definition, credential fields
  (`token`, `password`, `value`, `client-secret`) accept **only** `${secret:NAME}` references;
  a literal is rejected by the validator (build and import). `${secret:NAME}` resolves to the Spring
  property `workflow.http.secrets.NAME` (so env vars, K8s secrets, Vault — whatever the deployment
  already uses) **in the worker, at call time**; it never enters a task variable, the outbox, a log line
  or the process.
- **Auth types**: `none`, `basic`, `bearer`, `api-key` (header or query), `oauth2-client-credentials`
  (reusing Spring Security's client registrations; token cached until expiry, refreshed on 401 once).
- **Host allowlist (SSRF)**: absolute URLs must match `allowed-hosts`; independently, the resolved
  address is checked **at connect time** (so DNS rebinding cannot slip past) and loopback, private
  (RFC 1918 / ULA), link-local and the cloud metadata address are refused unless a connection names
  them explicitly. Connections are trusted configuration and are not subject to the allowlist.

### 3.5 Observability, UI, tooling

- Metrics: `eventconductor.http.calls{connection,status}` + latency timer;
  `eventconductor.events.published{destination}`.
- The HTTP span continues the process trace; the event carries its `traceparent`.
- The step's log records method, URL (query string redacted), status and duration — never headers or
  bodies (they may carry personal data; opt-in `log-bodies` per connection).
- Schema, graph node glyphs (globe for `HTTP_CALL`, broadcast for `PUBLISH_EVENT`), editor fields,
  IDE plugins, docs and AI reference files, as for `REPLY`.

## 4. Tests

- **Unit**: template rendering (typed leaves, JSON-in-a-variable, mixed strings, escaping, text
  templates, sandbox refusals, size guard), validation (build + import, secret-literal rejection),
  CloudEvents mapping, auth header construction per type, SSRF guard (allowlist, private/loopback/
  metadata, DNS answers), response mapping and error codes.
- **E2E (embedded)**: `PUBLISH_EVENT` delivered once through the `ExternalEventPublisher` port;
  `HTTP_CALL` against a local JDK `HttpServer`: success + output mapping, 4xx/5xx → failure →
  retries → compensation, timeout, idempotency key stable across retries, secrets resolved and never
  persisted, a `REPLY` built from an `HTTP_CALL`'s output on the synchronous fast path.
- **dist-e2e**: `PUBLISH_EVENT` over Kafka — published once per completed step, keyed; parked through
  a broker outage and delivered after; a pod crash between commit and relay does not lose it.
  `HTTP_CALL` through a Kafka worker running `worker-http`.

## 5. Phases (one PR each)

- [ ] **P0 — Plan & decisions.** This document reviewed; §6 resolved.
- [x] **P1 — Templates.** `modules/shared` template engine + validation (engine and plugin), `replyTemplate` on `REPLY`. — DONE:
      `TemplateSyntax` (JDK-only, in `definition-analysis`, so the Maven plugin parses templates exactly
      like the engine), `Templates` (in `shared`: structured render with typed leaves and JSON-in-a-variable,
      text render, `problems()` for validation), engine `TemplateContext`, `replyTemplate` (engine invariant,
      schema, plugin). Note: RESTRICTED JEXL makes a forbidden call read as `null` (non-strict), exactly as
      in the guards, rather than throw.
- [x] **P2 — `PUBLISH_EVENT`.** DSL, schema, `ExternalEventRequested`, destinations config, kafka routing, `ExternalEventPublisher` port (embedded), CloudEvents, tests incl. dist-e2e. — DONE.
      **DSL deviation:** the step's configuration is one nested `event:` block (`destination`, `type`, `key`,
      `payload` | `payloadTemplate` | `payloadVariables`, `format`) instead of flat fields — the YAML reads the
      same and the `Step` model and schema stay legible; `HTTP_CALL` gets an `http:` block likewise.
      Resolved in the step-over (like REPLY): `ExternalEventRenderer` checks the destination, renders payload
      and key, and the `ExternalEventRequested` is written to the outbox with the step's completion. Kafka:
      `OutboxRelay` → `ExternalEventSender` (binary / structured / plain, keyed, synchronous; an unconfigured
      destination at relay time is a poison message). Embedded: `ExternalEventRequestedHandler` →
      `ExternalEventPublisher` port, default `ApplicationEventExternalPublisher` (`ExternalEventPublished`).
      Never claimed by the inline drive. Tests: renderer, sender, DSL/invariants, plugin, `PublishEventE2eTest`
      + JPA twin, DIST-29 (CloudEvents binary on the real topic; events of processes created while the broker
      was paused delivered exactly once after it returns).
- [ ] **P3 — `HTTP_CALL`, engine side.** DSL, schema, validation (incl. secret literals), request rendering in `start()`, `http-call` dispatch.
- [ ] **P4 — `modules/worker-http`.** Connections, auth profiles (5 types), secrets, SSRF guard, idempotency key, trace propagation, response mapping, metrics; wired into `worker-embedded` apps and `worker-standalone-app`.
- [ ] **P5 — Tooling & docs.** Graph glyphs and editor fields, IDE plugins, docs (a guide page per step + configuration + AI reference files), CHANGELOG.

## 6. Decisions — RESOLVED (2026-09-24, owner accepted the recommendations)

1. **Kafka topic for `HTTP_CALL`**: a dedicated **`http-calls`** topic, served by default by the worker
   standalone app, so HTTP egress scales and is network-policed separately. A step's `topic` overrides.
2. **Retries**: a **4xx is not retried** even with `retries > 0`; 5xx, timeouts and I/O errors are.
   Default `retryOn: [5xx, io]`, overridable per step.
3. **Response mapping**: **JEXL** over `{status, headers, body}` — one language everywhere.
4. **CloudEvents on Kafka**: **binary** content mode by default (`ce_*` headers, data as the value);
   `structured` and `plain` selectable per destination or step.
5. **Embedded `PUBLISH_EVENT`**: a Spring **`ApplicationEvent`** only; an application with a broker
   provides its own `ExternalEventPublisher` bean.
