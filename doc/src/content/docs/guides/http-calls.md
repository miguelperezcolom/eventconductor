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

- **embedded mode**: add `io.mateu.workflow:worker-http` to the application; the call runs in the engine's pod (and on the synchronous fast path).
- **kafka mode**: the task goes to topic **`http-calls`** (a step's `topic` overrides), served by **`http-worker-standalone-app`** (image `http-worker-standalone-app`) or any worker with `worker-http` on its classpath — so HTTP egress scales and is network-policed apart from the engine.

## Responses, failures and retries

- A status in `successStatus` (default any 2xx) completes the step; each `output` expression is evaluated over `{status, headers, body}` — `body` parsed when it is JSON, `headers` by lower-case name.
- Any other status fails the step with `HTTP_<status>` and the start of the response body in the Errors tab; a connection error or timeout fails it with `HTTP_IO`.
- **`retryOn`** decides which failures the step's `retries` apply to: `5xx`, `4xx`, `io`, or specific codes. The default `[5xx, io]` means **a 4xx is not retried** — a 400 does not fix itself — and the saga's compensation runs at once.
- Every retry of a step sends the same **`Idempotency-Key`** (the step execution id), so a server that honours it applies a retried charge once. A connection can rename the header or turn it off (`idempotency-header: none`).

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
      partner-key:    { type: api-key, header: X-Api-Key, value: "${PARTNER_API_KEY}" }
      legacy:         { type: basic, username: svc, password: "${LEGACY_PASSWORD}" }
    secrets:
      PARTNER_TOKEN: "${PARTNER_TOKEN_FROM_VAULT}"
    allowed-hosts: [ "*.partner.com", "api.stripe.com" ]
    max-response-bytes: 1048576
```

- **Auth types**: `none`, `basic`, `bearer`, `api-key` (`in: header` or `query`), `oauth2-client-credentials` (token cached until it expires, refreshed once on a 401).
- **No secret literal in a definition.** Definitions live in git. In an inline `auth` block, credential fields (`token`, `password`, `value`, `clientSecret`) accept only `${secret:NAME}`, resolved **in the worker, at call time**, from `workflow.http.secrets.NAME` or the `NAME` property/environment variable. A literal fails the build; a `${secret:…}` anywhere else (a header, the body) fails it too — it would end up in a task variable.
- **Absolute URLs are guarded**: they must match `allowed-hosts` when it is set, and they may never reach an internal address — loopback, private ranges, link-local (including the cloud metadata endpoint `169.254.169.254`). Reach internal services through a **connection**, which is trusted configuration. (Pair it with an egress network policy: DNS rebinding between the check and the connection is not closed by the check alone.)
- Redirects are not followed. URLs are logged without their query string; bodies and headers are not logged.

Metric: `eventconductor.http.calls{target, status}` (timer).
