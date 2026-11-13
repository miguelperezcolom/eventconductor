---
title: HTTP Calls
description: The HTTP_CALL step — call a REST endpoint and map its response into the process, without writing a worker.
---

`HTTP_CALL` calls an HTTP endpoint and maps the response into process variables — the step you would otherwise write a worker for.

```yaml
- id: charge
  type: HTTP_CALL
  name: Charge the card
  preconditionStepId: book
  retries: 2
  timeout: PT15S
  compensable: true
  compensationStepId: refund
  http:
    connection: payments            # a named connection …
    method: POST
    path: "/charges/${bookingId}"   # … and a path under its base URL
    query: { currency: "${currency}" }
    headers: { X-Tenant: "${tenant}" }
    body:                           # a structured template (or bodyTemplate: text)
      amount: "${amount * 1}"
      reference: "Booking ${bookingId}"
    successStatus: [200, 201]       # default: any 2xx
    output:                         # variable ← JEXL over {status, headers, body}
      chargeId: "body.id"
      chargeStatus: "body.status"
    retryOn: [5xx, io]              # the default
```

Or with an absolute URL:

```yaml
  http:
    url: "https://api.partner.com/orders/${orderId}"
    auth: partner-key                                  # a named auth profile
    # auth: { type: bearer, token: "${secret:PARTNER_TOKEN}" }   # or inline, secrets by reference only
```

## How it runs

The engine renders the request — URL/path, query, headers and body are [payload templates](/guides/payload-templates/) — and dispatches it to the built-in task **`http-call@1`**. So everything a worker step has applies: `timeout`, `retries` with backoff, compensation, cancellation. The call itself is made by **`worker-http`**:

- **embedded mode**: add `io.mateu.workflow:builtin-workers` — one dependency for every built-in step (`worker-embedded`, `worker-http`, `worker-llm`, `worker-events`) — or just `io.mateu.workflow:worker-http` and `io.mateu.workflow:worker-embedded` (which runs the built-in task in-process) to the application; the call runs in the engine's pod (and on the synchronous fast path). Don't declare your own `EmbeddedTaskExecutor` bean then — it replaces `worker-embedded`'s and would receive the HTTP tasks too; write your workers as [task handlers](/guides/workers/).
- **kafka mode**: the task goes to topic **`http-calls`** (a step's `topic` overrides), served by the **orchestrator itself** (`orchestrator-standalone-app` runs the built-in workers, configured with the same `workflow.http.*` properties) — no worker to deploy. Optionally, **`http-worker-standalone-app`** (image `http-worker-standalone-app`) or any worker with `worker-http` on its classpath serves it instead — so HTTP egress and its secrets scale and are network-policed apart from the engine; then take `http-calls` out of the orchestrator's `BUILTIN_WORKER_TOPICS` and set `workflow.http.enabled=false` there (the Helm chart does both when `httpWorker.enabled`). Never let both serve the topic: two consumer groups run every call twice.

## Responses, failures and retries

- A status in `successStatus` (default any 2xx) completes the step; each `output` expression is evaluated over `{status, headers, body}` — `body` parsed when it is JSON, `headers` by lower-case name.
- Any other status fails the step with `HTTP_<status>` and the start of the response body in the Errors tab; a connection error or timeout fails it with `HTTP_IO`.
- **`retryOn`** decides which failures the step's `retries` apply to: `5xx`, `4xx`, `io`, or specific codes. The default `[5xx, io]` means **a 4xx is not retried** — a 400 does not fix itself — and the saga's compensation runs at once.
- Every retry of a step sends the same **`Idempotency-Key`** (the step execution id), so a server that honours it applies a retried charge once. A connection can rename the header or turn it off (`idempotency-header: none`).

## Not creating duplicates in the target system

Delivery is **at least once**: a step can reach the target more than once — a retry after a
timeout, a worker that dies after the call but before reporting, a broker redelivery. And a
response from an attempt the engine has already given up on is dropped, so the target may have
created the resource without the process knowing. Whether that ends in a duplicate is up to the
target; the step's job is to give it what it needs to recognise a repeat. Two patterns, both plain
`HTTP_CALL`s — no worker code.

### Pattern 1 — an idempotency key

If the target honours an idempotency key (or a unique external reference), send one. The engine
already sends the step execution id as `Idempotency-Key` on every call, the same on each retry of
the step. That covers retries, but not a step that runs again as a new execution (a retry from an
earlier step, a restarted process) or the same business request arriving as a second process. For
those, derive the key from the business data instead — a header with the **same name** replaces the
automatic one (the match is exact, case included):

```yaml
- id: create
  type: HTTP_CALL
  retries: 3
  http:
    connection: hotel
    method: POST
    path: /bookings
    headers:
      Idempotency-Key: "${businessKey}-${room.number}"   # replaces the step-execution-id key
    body:
      externalRef: "${businessKey}-${room.number}"       # also stored, for lookups and cancellation
      room: "${room.number}"
    output:
      roomBookingId: "body.id"
```

| Key | Step retries | Retry-from-step / restart | Same request as a new process |
|---|---|---|---|
| automatic (step execution id) | ✅ | ❌ new execution, new key | ❌ |
| business key (e.g. `businessKey` + room) | ✅ | ✅ | ✅ |

The business key only works if its parts are stable and unique: the process's `businessKey` set to
the source booking's id, and a per-item field that cannot repeat within it (a line id is safer
than a room number if one booking can hold the same room twice). To stop the same request from
starting a second process in the first place, start it through a
[synchronous invocation](/guides/synchronous-invocation/), whose `Idempotency-Key` joins the
existing process instead.

### Pattern 2 — look up before creating

If the target has no idempotency key but can be searched by an external reference, look first and
create only when nothing is there. `successStatus` makes a 404 an answer rather than a failure, and
a [`CHOICE`](/reference/step-types/#choice) takes the branch:

```yaml
- id: lookup
  type: HTTP_CALL
  http:
    connection: hotel
    method: GET
    path: "/bookings/by-ref/${businessKey}-${room.number}"
    successStatus: [200, 404]
    output:
      found: "status == 200 ? 'true' : 'false'"
      roomBookingId: "status == 200 ? body.id : ''"

- id: exists
  type: CHOICE
  preconditionStepId: lookup

- id: create                      # not there yet → create it
  type: HTTP_CALL
  preconditions: [ { stepId: exists, expression: "found == 'false'" } ]
  http:
    connection: hotel
    method: POST
    path: /bookings
    body: { externalRef: "${businessKey}-${room.number}", room: "${room.number}" }
    output:
      roomBookingId: "body.id"

- id: confirm                     # already there, or just created
  type: ACTION
  topic: confirm-booking
  preconditions:
    - { stepId: exists, expression: "found == 'true'" }
    - { stepId: create }
  joinType: XOR
```

There is a window: two attempts running *at the same time* can both see "not found" and both
create. Sequential retries — the normal case — do not open it. When the target supports both
patterns, prefer the key: one call, no window.

### Compensation must be idempotent too

The undo step is retried and redelivered like any other, and it may run for a creation whose
response the process never saw. So, for a `cancelRoom` compensation:

- cancelling twice succeeds;
- cancelling something that does not exist succeeds (the create may never have arrived) — list
  `404` in its `successStatus`;
- it addresses the resource by the **business key** (`externalRef`), not only by the id the
  create returned — that id is missing when the create's response was lost.

```yaml
- id: cancelRoom
  type: HTTP_CALL
  http:
    connection: hotel
    method: DELETE
    path: "/bookings/by-ref/${businessKey}-${room.number}"
    successStatus: [200, 204, 404]
```

With a [`forEach`](/guides/loops-and-multi-instance/) over the rooms, each iteration renders its own
`room`, so every room gets its own key; the compensation runs once per completed iteration and sees
that iteration's `room` and everything it reported.

## Waiting for a callback

When the response only acknowledges the request and the real answer comes later — a webhook, a callback, an event — give the step an `awaitReply` (`messageName`, `correlationExpression` and an optional `onEvent`, as on a `WAIT_FOR_MESSAGE`). The wait is armed before the task is dispatched, so a callback sent even before the response is caught. A 2xx records `output` and the step keeps waiting; the reply completes it — and a reply that comes before the response completes it on its own, the late response then ignored. See [Asynchronous Request/Response](/guides/request-response/#the-same-with-an-http-request).

## Connections, authorization and secrets

```yaml
workflow:
  http:
    connections:
      payments:
        base-url: https://payments.internal
        auth: payments-oauth                 # default auth for this connection
        connect-timeout: PT2S
        read-timeout: PT10S
        headers: { X-Client: eventconductor }
    auth:
      payments-oauth: { type: oauth2-client-credentials, token-uri: https://idp/oauth/token,
                        client-id: eventconductor, client-secret: "${PAYMENTS_CLIENT_SECRET}", scope: payments }
      partner-key:    { type: api-key, header: X-Api-Key, value: "${PARTNER_API_KEY}",
                        hosts: [ "api.partner.com" ] }    # where an absolute url may receive it
      legacy:         { type: basic, username: svc, password: "${LEGACY_PASSWORD}" }
    secrets:
      PARTNER_TOKEN: "${PARTNER_TOKEN_FROM_VAULT}"
    allowed-hosts: [ "*.partner.com", "api.stripe.com" ]
    max-response-bytes: 1048576
```

- **Auth types**: `none`, `basic`, `bearer`, `api-key` (`in: header` or `query`), `oauth2-client-credentials` (token cached until it expires, refreshed once on a 401).
- **No secret literal in a definition.** Definitions live in git. In an inline `auth` block, credential fields (`token`, `password`, `value`, `clientSecret`) accept only `${secret:NAME}`, resolved **in the worker, at call time**, from `workflow.http.secrets.NAME` or the `NAME` property/environment variable. A literal fails the build; a `${secret:…}` anywhere else (a header, the body) fails it too — it would end up in a task variable.
- **Which values a definition can reach.** Only what is listed under `workflow.http.secrets` (map environment variables into it explicitly: `PARTNER_TOKEN: "${PARTNER_TOKEN}"`). Since 2.27.0 that is the default; before, `${secret:NAME}` fell back to *any* property or environment variable of the worker, so a definition author could send a database password or a cloud credential to a host of their choosing. `workflow.http.secrets-from-environment: true` restores that fallback — then restrict `allowed-hosts`; the worker logs a WARN at startup while the fallback is on and `allowed-hosts` is empty.
- **Absolute URLs are guarded**: they must match `allowed-hosts` when it is set, and they may never reach an internal address — loopback, private ranges, carrier-grade NAT (`100.64.0.0/10`), benchmarking (`198.18.0.0/15`), link-local (including the cloud metadata endpoint `169.254.169.254`), and the same addresses embedded in IPv6 (IPv4-mapped, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`). A host that does not resolve is refused rather than attempted (a retryable `HTTP_IO`). The `token-uri` of an **inline** `oauth2-client-credentials` block is checked the same way before the client secret is posted to it, and its response is capped at `max-response-bytes`; a named auth profile's `token-uri` is configuration and is trusted. Reach internal services through a **connection**, which is trusted configuration. (Pair it with an egress network policy: DNS rebinding between the check and the connection is not closed by the check alone.)
- **Credentials are bound to hosts** (since 2.28.0). A step chooses both its URL and its `auth`, so without a binding a definition such as `url: https://attacker.example, auth: payments-oauth` would hand the operator's credentials to anyone. Checked in the worker before any secret is resolved or token fetched; a refusal fails the step with the non-retryable `HTTP_FORBIDDEN_AUTH`:
  - a **named profile** goes through a **connection** (it is bound to that connection's base URL), or to an absolute URL whose host matches the profile's **`hosts`** (`api.partner.com`, `*.partner.com`). With no `hosts`, an absolute URL may use it only when `allowed-hosts` is set (the host already passed that list). When `hosts` is set it restricts connections too;
  - an **inline block** whose credentials are `${secret:…}` references goes through a connection, or to an absolute URL only when `allowed-hosts` is set;
  - `workflow.http.credentials-to-any-host: true` restores the pre-2.28 behaviour (not recommended).
- Redirects are not followed. URLs are logged without their query string; bodies and headers are not logged.

Metric: `eventconductor.http.calls{target, status}` (timer).
