# Plan: integrations — credentials, connectors, polling triggers, transforms and trying a step

> Status: **DRAFT (2026-10-19)** — written to answer "could EventConductor cover n8n's use cases?".
> §7 lists the open decisions with a recommendation each; nothing is implemented. One PR per phase;
> each phase compiles and tests green on its own.

## 1. Goal

Cover the **integration automation** use cases people reach n8n for — *"when a row is added to this
sheet, enrich it with an API, ask a model to classify it, post to Slack and open a Jira ticket"* —
without turning EventConductor into an n8n clone and without giving up what n8n lacks: durable state,
sagas with compensation, distributed mode, human tasks, sync invocation, operating running processes.

The engine side is mostly there. What is missing is around it:

| n8n has | EventConductor today | Gap |
|---|---|---|
| Webhook node | `webhook-gateway` (generic + GitHub, signatures) | — |
| Schedule trigger | `schedules` / cron | — |
| HTTP Request node | `HTTP_CALL` (connections, 5 auth types, SSRF guard) | — |
| AI nodes | `LLM_CALL` | Agent-with-tools: out of scope here (§6) |
| IF / Switch / Merge / Loop / Wait | `CHOICE`, `FORK`/`JOIN`, `forEach`/`repeat`, `TIMER`, `WAIT_FOR_MESSAGE` | — |
| Event triggers from SaaS | Kafka/REST domain-event `triggers` | **Polling triggers** (§3.3) |
| ~400 app nodes | Hand-written workers or raw `HTTP_CALL` | **Connectors** (§3.2) |
| Credentials UI + OAuth2 consent | Secrets in configuration only | **Credential store + OAuth2 authorization code** (§3.1) |
| Set / Code / Item Lists nodes | Templates inside other steps | **`TRANSFORM` step** (§3.4) |
| "Execute node" with pinned data | Run the whole process | **Try a step** (§3.5) |
| Browser canvas editor | `.ec` files in IntelliJ / VS Code, read-only graph in the admin UI | Out of scope here (§6, decision 7) |

Positioning: *n8n for processes that must not fail*. The MVP is §3.1 + §3.2 + §3.3 + §3.5.

## 2. What exists to build on

- **`HTTP_CALL`** (`HTTP-AND-EVENT-STEPS-PLAN.md`): request rendered in `StepExecution.start()` into the
  `__http` task variable, dispatched as `http-call@1` on topic `http-calls`, served by `modules/worker-http`
  (`HttpCallHandler`, `HttpWorkerProperties` with `connections` / `auth` / `secrets`, `SecretResolver`,
  `HostGuard`, `OAuth2TokenCache` for client credentials). Structural rules shared with the Maven plugin
  in `definition-analysis` (`HttpCallRules`). Non-retryable failures via `FailureMarkers.NON_RETRYABLE`.
- **Secrets**: `${secret:NAME}` is the only form a credential takes in a definition; resolved in the
  worker at call time from `workflow.http.secrets.NAME` (optionally the environment). Never enters a
  task variable, the outbox or a log.
- **Task contracts**: `.ectask` files → `TaskContract(id, version, group, topic, description, input,
  output, errors)` in `shared`; `TaskReferenceResolver` pins `task: <id>` to `<id>@<latest>` at import;
  the Maven plugin validates; `worker-codegen` generates records/interfaces from them.
- **Event ingest** (`DOMAIN-EVENT-TRIGGERS-PLAN.md`): `InboundEventService.receive(InboundEvent)` is the
  single place events are matched, de-duplicated (receipts `(receiver, source, id)`) and turned into
  starts and messages; definition-level `triggers: [...]` with filter / businessKey / variables templates;
  pause/resume, Events and Received events pages, REST and MCP.
- **Schedulers**: `CronStartScheduler` and friends run as `SchedulerPass`es under a DB advisory lock
  (jpa) or in memory; starts go through `IngressRouter` as `SYSTEM`.
- **Templates and JEXL sandbox**: `Templates` / `TemplateSyntax` (structured with typed leaves, text),
  `JEXLEvaluator` + `ExpressionGuard` (RESTRICTED permissions, size guard).
- **Tooling**: the JSON schema, the IDE plugins (where definitions are authored), the admin UI
  (Mateu, read-only for definitions by design — `WorkflowDefinitionDetailView`), MCP tools.

## 3. Design

### 3.1 Credentials — a managed store, and OAuth2 authorization code

Configuration-held secrets stay as they are (they are right for platform teams). Added: **credentials**
created at runtime by an operator, stored encrypted, usable wherever an auth profile is.

```yaml
# a step, an auth profile or a connector connection names a credential instead of inline secrets
http:
  connection: sheets
  auth: { credential: google-acme }       # new: a credential from the store
```

**Model.** `Credential(name, type, fields, scopes, owner, createdAt, updatedAt, lastUsedAt, status)`.
Types: the five auth types `HTTP_CALL` already knows (`basic`, `bearer`, `api-key`,
`oauth2-client-credentials`, `none`) plus **`oauth2-authorization-code`** (access token, refresh token,
expiry, granted scopes, and the client it was granted to). The client itself (client id/secret,
authorize and token URIs) is an **OAuth2 app**, configuration or store, shared by many credentials.

**Storage.** Table `workflow_credential` (jpa, next free migration) or in memory. Secret fields are
encrypted with AES-256-GCM under a data key; the data key is wrapped by a **`CredentialKeyProvider`**
port — default: a master key from `workflow.credentials.master-key` (env / K8s secret), with Vault
Transit / cloud KMS as later adapters. No master key configured → the store is disabled (read-only
config secrets keep working), never plaintext.

**OAuth2 authorization code.** Admin UI → *Credentials → New → Google* → redirect to the provider with
`state` (signed, single-use, 10 min) and PKCE → callback `GET /workflow/oauth2/callback` on the engine
→ tokens stored encrypted. The **engine owns refresh** (one refresher, under the credential's row lock),
because providers rotate refresh tokens and two pods refreshing concurrently would invalidate each other.
A credential whose refresh fails is marked `NEEDS_REAUTH` (UI badge, metric, incident on the steps that
use it).

**Resolution by mode.**
- *embedded*: the HTTP worker calls the `CredentialResolver` port in-process.
- *kafka*: `worker-http` asks the engine's internal endpoint
  `POST /workflow/internal/credentials/{name}/access` (service API key, network-policed) and caches the
  answer until shortly before expiry. It receives **headers-ready material only** (an access token, a
  basic pair, an API key) — never a refresh token or a client secret.

`SecretResolver` becomes a chain — `${secret:NAME}` from configuration as today; `{ credential: NAME }`
from the store — so nothing that works today changes.

**Operations.** Admin UI *Workflow → Credentials* (list, create, re-authorize, rotate, delete — values
never shown again after save), REST `/workflow/api/credentials`, MCP (list/status only; MCP never
receives or sets secret values). Audit log of create/rotate/delete/use-denied. Deleting a credential
in use is refused with the list of definitions that name it.

**Validation.** `{ credential: NAME }` must be a name (no literals); an unknown credential is not an
import error (created after import is normal) — the step fails non-retryable at runtime with a clear
reason, and the definition detail view flags it.

### 3.2 Connectors — typed operations over `HTTP_CALL`

A **connector** is a versioned file describing one API: base URL, auth requirements and operations,
each with typed input/output and the HTTP binding. It lives beside `.ectask` files and is imported the
same way.

```yaml
# slack.ecconnector
id: slack
version: 1
title: Slack
baseUrl: https://slack.com/api
auth: { types: [oauth2-authorization-code, bearer], scopes: [chat:write] }
operations:
  chat.postMessage:
    title: Post a message
    method: POST
    path: /chat.postMessage
    input:
      channel: { type: string, required: true }
      text:    { type: string, required: true }
      thread_ts: { type: string }
    body: { channel: "${channel}", text: "${text}", thread_ts: "${thread_ts}" }
    output:
      ts: { type: string, from: "body.ts" }
    errorWhen: "body.ok == false"         # Slack answers 200 with ok=false
    errors:
      channel_not_found: { when: "body.error == 'channel_not_found'", retryable: false }
```

In a definition:

```yaml
- id: notify
  type: HTTP_CALL
  name: Tell the team
  http:
    operation: slack/chat.postMessage     # pinned to slack@1/chat.postMessage at import
    auth: { credential: slack-acme }
    input:
      channel: "#bookings"
      text: "New booking ${bookingId} for ${guestName}"
  preconditionStepId: confirm
```

**Execution: expansion at import, nothing new at runtime.** A `ConnectorResolver` (next to
`TaskReferenceResolver`, run on every import path) pins the version and expands `operation` + `input`
into the plain `http:` block `HTTP_CALL` already executes: method, URL, body template, response mapping,
error rules. The stored definition keeps the original step *and* the expansion, so the graph shows
"Slack · Post a message" and the runtime, retries, SSRF guard, metrics and logs are `HTTP_CALL`'s.
Two additions to `HTTP_CALL` the connectors need, useful on their own: `errorWhen` (a JEXL over the
response that turns a 2xx into a failure) and named `errors` with `retryable`.

**Validation** (engine on import and Maven plugin, one class in `definition-analysis`): the operation
exists, required inputs are present, no unknown inputs, input templates parse, the credential's type is
one the connector accepts (runtime check — the store is not known at build).

**Where connectors come from.**
1. **Generated from OpenAPI**: a Maven plugin goal / CLI
   `ec connector import --spec openapi.yaml --id stripe --operations 'createCustomer,createPaymentIntent'`
   picks operations (all of a large spec is noise), maps parameters/request body to `input`, the success
   response schema to `output`, security schemes to `auth`. Output is an editable `.ecconnector` file —
   generation is a starting point, the file is the source of truth.
2. **Bundled pack** (`modules/connectors`, a jar of `.ecconnector` resources, opt-in on the classpath):
   a first wave chosen by what the use cases need, not by count — Slack, Microsoft Teams (Graph), Google
   Sheets, Gmail send, Jira, GitHub, Stripe, a generic "send an email" over SMTP (a built-in
   `send-email@1` task in a small `worker-mail`, since SMTP is not HTTP).
3. **The user's own APIs**: the same generator over their internal OpenAPI — arguably the most valuable
   case in a company, and where n8n's catalog does not help.

**Tooling.** Schema for `.ecconnector`; IDE plugins complete `operation:` and its `input:` keys from
the imported connectors; graph node shows the connector's title/icon; admin UI *Workflow → Connectors*
lists them (read-only, like task contracts); MCP `listConnectors`, `describeConnectorOperation` — so the
AI agent can author definitions with them.

### 3.3 Polling triggers

For systems that cannot push: *"every 5 minutes, new rows in this sheet"*, *"new issues in this Jira
query"*. A third kind of trigger, beside cron and domain events, that **feeds the existing event ingest**.

```yaml
triggers:
  - id: new-rows
    poll:
      every: PT5M
      operation: google-sheets/values.get          # or a plain http: block
      auth: { credential: google-acme }
      input: { spreadsheetId: "1AbC…", range: "Bookings!A2:F" }
      items: "body.values"                         # JEXL over the response → a list
      itemId: "${item[0]}"                         # template → stable id of an item (dedupe)
      cursor: "${max(items, i -> i[5])}"           # optional; available as ${cursor} on the next poll
    variables:
      bookingId: "${data[0]}"
      guest:     "${data[1]}"
```

**How it runs.**
- A `PollTriggerScheduler` (a `SchedulerPass` under its own advisory lock, like `CronStartScheduler`)
  finds polls that are due and not paused, and **dispatches the call to the HTTP worker** as task
  `http-poll@1` — the call must go through the same egress, credentials and SSRF guard as `HTTP_CALL`,
  and in kafka mode the engine pod is not the one with egress.
- The worker performs the call, evaluates `items` / `itemId` / `cursor`, and answers with the item list
  (bounded: `workflow.poll.max-items`, default 500) and the new cursor.
- The engine turns each item into an `InboundEvent` (source `poll://<definition>/<trigger>`, id =
  `itemId`, `data` = the item) and hands it to `InboundEventService.receive`, **scoped to that trigger**.
  The existing receipts make it exactly-once per item; the existing filter/businessKey/variables
  templates apply unchanged. Then it stores the cursor and `nextRunAt`.
- **First run**: by default records every current item as seen without starting anything
  (`initial: skip`, n8n's behaviour); `initial: start` processes the backlog.

**State.** `poll_trigger_state(definition, trigger, cursor, next_run_at, last_run_at, last_outcome,
consecutive_failures)`. Failures back off exponentially (cap `every` × 8) and after N consecutive
failures raise an incident on the Events page. Pause/resume reuses `event_trigger_pause`.

**Why not a looping process per poll?** It would work with today's steps (TIMER + HTTP_CALL + forEach),
but every poll would leave a process instance, the dedupe would be the author's problem, and the Events
page would not see it. A trigger is the right shape; the docs will still show the process form for
cases that need custom logic between polls.

### 3.4 `TRANSFORM` — reshape data without a worker

n8n flows are half data-shaping (Set, Code, Item Lists). Today a template lives inside another step's
configuration; there is no step whose only job is to compute variables.

```yaml
- id: shape
  type: TRANSFORM
  name: Prepare the summary
  set:                                    # structured templates, typed leaves (Templates)
    total:     "${sum(items, i -> i.price * i.qty)}"
    vipItems:  "${filter(items, i -> i.price > 100)}"
    byCountry: "${groupBy(guests, g -> g.country)}"
    title:     "Booking ${bookingId} · ${formatDate(checkIn, 'dd MMM yyyy')}"
  unset: [ rawResponse ]                  # optional; drop large intermediates
  preconditionStepId: fetch
```

- **Engine-internal**, resolved in the step-over like `REPLY` / `PUBLISH_EVENT`: render, write
  variables, complete. No dispatch, no worker, on the sync fast path.
- **A curated function library** for the JEXL sandbox (`io.mateu.workflow.template.functions`, in
  `shared`): `map`, `filter`, `find`, `sum`, `min`, `max`, `count`, `groupBy`, `sortBy`, `distinct`,
  `flatten`, `chunk`, `keys`, `values`, `pick`, `omit`, `merge`, `formatDate`, `parseDate`, `addDuration`,
  `lower`, `upper`, `trim`, `split`, `join`, `regexReplace` (linear-time), `jsonParse`, `jsonStringify`,
  `uuid`. The lambdas above need the sandbox to allow JEXL lambdas **only as arguments to these
  functions** — an `ExpressionGuard` rule, not a permission change (decision 4).
- The library is available to every template (HTTP bodies, `REPLY`, triggers, `LLM_CALL` prompts), not
  only `TRANSFORM`.
- Errors fail the step (`ERROR`) like a failing REPLY expression; no silent nulls in `set`.
- **No general-purpose code** (JS/Python) in this plan — decision 5.

### 3.5 Try a step — the "execute node" loop

n8n's tight loop: edit a node, run it alone against sample data, look at the output. Here the editor is
the IDE, so the loop belongs in the **plugins**, backed by an engine endpoint.

`POST /workflow/api/try/step` with `{definition (the .ec text), stepId, variables, mode: render|execute}`:
- `render` — validate and render everything the step would send (HTTP request with headers redacted,
  LLM prompt, event payload, `TRANSFORM` result, rule inputs) without side effects.
- `execute` — really run it, **only** for `HTTP_CALL` (incl. connectors), `LLM_CALL`, `TRANSFORM`,
  `RULE` and a poll trigger's call; synchronously, through the same worker path, outside any process
  (no process, no outbox, no compensation). Returns the response, the mapped variables and the duration.
- Guarded by `workflow.try.enabled` (default **false**; on in `dev-app` and the compose demo), the admin
  role, the same SSRF/credential rules, and a per-user rate limit.

**Sample data.** The plugin keeps per-definition sample variables in a sidecar
`<name>.ec.samples.yaml` (git-friendly, like n8n's pinned data), and can capture them from a real
process run (*"use the variables this step saw in process X"* via the existing process API).

**Plugins.** A ▶ gutter action on each step (IntelliJ and VS Code): pick a sample, render or execute,
show request/response side by side; "chain" mode runs the next step with the previous step's output
merged into the sample.

## 4. Tests

- **Unit**: credential encryption round-trip and key wrapping, refusal without a master key, OAuth2
  state/PKCE, refresh single-flight, `NEEDS_REAUTH`; connector parsing, expansion, validation, OpenAPI
  generation (fixtures: Stripe/Slack/petstore subsets); `errorWhen` / named errors; poll item
  extraction, `itemId` / cursor, bounded lists, initial skip; every function in the library and the
  lambda-only-as-argument guard; try-step render per step type and its guards.
- **E2E (embedded)**: an `HTTP_CALL` via a connector against a local JDK `HttpServer` with a stored
  bearer credential; an authorization-code credential refreshed once by two concurrent steps; a poll
  trigger starting one process per new row and none for seen rows across restarts (jpa twin); a
  `TRANSFORM` on the sync fast path feeding a `REPLY`; try-step execute returning a mapped response.
- **Dist (Kafka)**: `worker-http` fetching credentials from the engine's internal endpoint; a poll
  served by the standalone HTTP worker; a broker outage during a poll (no duplicates after it returns).
- **Security**: secret values absent from logs, outbox, task variables, MCP and REST responses
  (asserted by scanning captured output for the test secrets); internal credential endpoint rejects
  missing/invalid service keys; try-step disabled by default.

## 5. Phases (one PR each)

- [ ] **P0 — Plan & decisions.** This document reviewed; §7 resolved.
- [ ] **P1 — `TRANSFORM` + function library.** Small, independent, immediately useful; makes every later
  phase's examples readable.
- [ ] **P2 — Credential store.** Model, encryption, `CredentialKeyProvider`, jpa/memory, `{ credential }`
  in auth, resolver chain, internal endpoint for kafka workers, admin UI page, REST, audit.
- [ ] **P3 — OAuth2 authorization code.** OAuth2 apps, consent flow and callback, engine-owned refresh,
  `NEEDS_REAUTH`, incidents.
- [ ] **P4 — Connectors.** `.ecconnector` schema and model, import, `ConnectorResolver`, `errorWhen` and
  named errors on `HTTP_CALL`, validation (engine + Maven plugin), graph, admin page, MCP.
- [ ] **P5 — OpenAPI generator.** Maven goal + CLI; fixtures.
- [ ] **P6 — Bundled connector pack + `worker-mail`.** First wave (§3.2), each with an example definition.
- [ ] **P7 — Polling triggers.** Model and rules, scheduler, `http-poll@1` in `worker-http`, state,
  back-off, incidents, Events page; dist test.
- [ ] **P8 — Try a step.** Endpoint and guards; IntelliJ and VS Code actions; samples sidecar.
- [ ] **P9 — Docs & examples.** Guides (*Credentials*, *Connectors*, *Polling*, *Transforming data*,
  *Trying a step*), "coming from n8n" page mapping nodes to steps, a gallery of example definitions,
  configuration and AI reference files, skill, CHANGELOG.

MVP for a demo against n8n: P1, P2, P4 (with two or three hand-written connectors), P7, P8.

## 6. Out of scope (separate plans if wanted)

- **Browser canvas editor.** The admin UI is deliberately read-only for definitions (`.ec` files in git,
  IDE plugins). A visual editor is the biggest single item for a non-developer audience and changes who
  owns definitions (UI vs git — the `schedules` file/UI ownership problem, multiplied). Decision 7.
- **AI agent with tools** (n8n's AI Agent node): `LLM_CALL` + `CHOICE` + loops cover classify/extract/
  draft; a model choosing tools at runtime is what `ia-agent-service` explores. A later plan could expose
  connector operations as tools to an `LLM_CALL` with `tools:`.
- **Database nodes** (SQL query/insert): a `worker-sql` with named datasources is easy to write but a
  sharp tool (injection, long transactions); wait for a concrete case.
- **Per-item execution semantics** (n8n runs each node once per item): not copied; `forEach` with
  multi-instance is the explicit form.
- **Multi-tenant credential sharing / RBAC per credential**: credentials are engine-wide, admin-managed
  in this plan.

## 7. Decisions — OPEN (recommendation first)

1. **Who refreshes OAuth2 tokens** — **the engine** (single refresher, row lock; workers receive access
   tokens only). *Alternative:* each worker refreshes — simpler in embedded, broken by refresh-token
   rotation in kafka mode.
2. **Connectors at runtime** — **expanded into `HTTP_CALL` at import**, pinned version. *Alternative:* a
   `connector-call@1` task resolving the operation in the worker — needs connector files in every worker
   and a second runtime path for the same thing.
3. **Polls executed by** — **the HTTP worker** (`http-poll@1`), results ingested by the engine.
   *Alternative:* the engine pod calls out directly — simpler, but breaks the "egress only from
   `http-calls` workers" network model of kafka deployments.
4. **Lambdas in JEXL** — **allowed only as arguments to library functions** (guard rule). *Alternative:*
   a library without lambdas (`filterBy(items, 'price', '>', 100)`) — safer to reason about, clumsier.
5. **General-purpose code step (GraalJS / Python)** — **not now**. The function library plus workers
   (any language over Kafka) cover it; a sandboxed polyglot runtime is a large security surface in the
   engine. Revisit with real cases that the library cannot express.
6. **Master key** — **required for the store, no plaintext fallback**; KMS/Vault adapters later.
7. **Visual editor** — **separate plan, after the MVP**, and decided by audience: if the target user of
   these integrations is a developer, IDE + try-a-step is enough; if it is an operations/business user, a
   web editor (writing `.ec` back to git through the existing git import) is the next plan.
8. **Bundled connector pack** — **a separate opt-in module** (`modules/connectors`), released with the
   engine; connectors are data, so updating one does not need an engine release later (versioned files).
