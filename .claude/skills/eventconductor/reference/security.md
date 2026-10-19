# Security, credentials and sensitive data

Secure by default since 2.27/2.28: nothing the engine serves is open to anonymous callers unless you
say so. Canonical: `doc/src/content/docs/reference/configuration.md` (HTTP security),
`guides/http-calls.md`, `guides/sensitive-data-and-encryption.md`.

## Who may call the engine

**REST API `/workflow/api/**`** (processes, messages, events, triggers, schedules, invocations,
updates, operations, incidents, batch operations, workers…) lets a request in when ONE holds:

1. `workflow.message-api.api-key` is set and the request sends it as `X-Api-Key`;
2. no key is set and the app has a Spring Security filter chain (any chain counts, even permit-all);
3. no key, no chain, and `workflow.message-api.allow-anonymous=true`.

Otherwise **401**. The startup log says which rule applies.

**Consoles** — admin UI (`/_workflow`, `/_workflow-admin`, `/_forms…`, `/_rules`), MCP (`/mateu/mcp`),
`GET /rules[/{id}]`, task-project zips: only with a Spring Security chain or `allow-anonymous=true`
(the API key does NOT open them; a browser cannot send it) — otherwise **403**.

| Setting | Effect |
|---|---|
| `workflow.message-api.allow-anonymous=true` | opens API + consoles. Local/isolated only |
| `workflow.message-api.api-key=<key>` | API by `X-Api-Key` (consoles stay closed without Spring Security) |
| `workflow.ui.enabled=false` | admin UI not mounted at all (404) |
| `spring.ai.mcp.server.enabled=false` | switch an MCP server off |
| `@EnableWorkflowUi` (`io.mateu.workflow.autoconfigure`) | needed beside `@WorkflowEmbeddedApplication` when your app mounts `WorkflowMenu`/`FormsMenu`/`RulesMenu` (since 2.28) |
| `workflow.security.trust-forwarded-token=true` | trust an unverified bearer token's claims (`workflow.security.claims.*`) — only behind a verifying gateway |
| `workflow.security.flow-authorization.enabled=true` | enforce a definition's `requiredScopes`/`requiredRoles` on start, reads, commands, schedules, triggers (403) |
| `workflow.security.operations.required-roles` | roles for manual operations (set variables, complete step, retry-from, incident retry) |
| `workflow.security.sensitive.reveal-roles` | roles allowed to reveal a sensitive variable (default: nobody) |

Standalone apps (`apps/*-standalone-app`) run Spring Security with HTTP Basic:
`SECURITY_ENABLED` (default `true`), `SECURITY_USER` (`admin`), `SECURITY_PASSWORD` — set it; when
blank a random one is generated and never printed (nobody can log in). `apps/docker-compose.yml`
uses `changeme` unless exported. A standalone app with `SECURITY_ENABLED=false` has a permit-all chain,
so its API is open — set an api key if reachable.

Local dev, embedded, no Spring Security:
```properties
workflow.message-api.allow-anonymous=true
```
Production embedded: add Spring Security (any `SecurityFilterChain` bean), or an api key + `workflow.ui.enabled=false`.

## HTTP_CALL credentials (worker-http)

- Connections: `workflow.http.connections.<name>.{base-url, auth, connect-timeout, read-timeout, headers.*, idempotency-header}`.
- Auth profiles: `workflow.http.auth.<name>.type` = `none|basic|bearer|api-key|oauth2-client-credentials`,
  fields `username, password, token, header, value, in (header|query), token-uri, client-id, client-secret, scope, hosts`.
- Secrets: `${secret:NAME}` in an inline `auth` block resolves ONLY from `workflow.http.secrets.<NAME>`
  (2.27; `workflow.http.secrets-from-environment=true` restores env lookup). Literal credentials in a
  definition fail validation.
- **Host binding (2.28):** a named profile is applied through a `connection` (bound to its base URL), or
  on an absolute `url` only when the host is in `workflow.http.auth.<name>.hosts` (`api.partner.com`,
  `*.partner.com`) — or, with no `hosts`, when `workflow.http.allowed-hosts` is set. Inline
  `${secret:…}` on an absolute URL needs `allowed-hosts`. Else the step fails, non-retryable,
  `HTTP_FORBIDDEN_AUTH`. (`workflow.http.credentials-to-any-host=true` = old behaviour, not recommended.)
- Absolute URLs: `workflow.http.allowed-hosts` (empty = any public host); internal/private addresses
  are always refused (`HTTP_FORBIDDEN_HOST`) — reach internal services through a `connection`.
- Error codes: `HTTP_<status>` (retried per `retryOn`, default `[5xx, io]`), `HTTP_IO`,
  `HTTP_FORBIDDEN_AUTH`, `HTTP_FORBIDDEN_HOST`, `HTTP_CONFIG`, `HTTP_REQUEST`, `HTTP_RESPONSE_TOO_LARGE`.

```yaml
# application.yaml
workflow:
  http:
    connections:
      payments: { base-url: "https://payments.example.com", auth: payments-key }
    auth:
      payments-key: { type: api-key, header: X-Api-Key, value: "${PAYMENTS_API_KEY}" }
      partner-oauth:
        type: oauth2-client-credentials
        token-uri: https://auth.partner.com/oauth/token
        client-id: ec
        client-secret: "${PARTNER_SECRET}"
        hosts: [ "api.partner.com" ]          # required to use it on an absolute url
    secrets:
      PARTNER_TOKEN: "${PARTNER_TOKEN}"      # what ${secret:PARTNER_TOKEN} may resolve to
```

LLM keys: ONLY `workflow.llm.connections.<name>.api-key` (`provider: anthropic|openai`, `model`,
`base-url`, `max-tokens`, `timeout`, `structured-output`). A key or `${secret:}` in a definition fails validation.

## Sensitive variables (2.32)

```yaml
sensitiveVariables: [ cardNumber, "patient.*" ]   # names or globs, part of the version
```
- A step's `sensitiveOutputs: true` adds its `outputVariables` (PROCESS) to the set.
- **Needs keys** or the definition is refused: `workflow.encryption.keys.<id>` = base64 of 32 bytes
  (`openssl rand -base64 32`), `workflow.encryption.active-key=<id>` (required with several keys; rotate by
  adding a key and switching `active-key`; old keys keep decrypting). An engine whose store holds
  encrypted values does not start without them. SPI: `EncryptionKeySource`, `VariableCodec` (KMS/Vault).
- Values are AES-256-GCM sealed (`ec:enc:v1:<keyId>:…`) in process/step rows, outbox, Kafka, the HTTP
  task queue, forms, batch parameters; masked `••••` in console/REST/MCP; logs scrubbed.
- Reveal: `POST /workflow/api/processes/{id}/variables/{name}/reveal {"reason": "..."}` or MCP
  `revealProcessVariable`, only for `workflow.security.sensitive.reveal-roles`; audited. An API key alone never reveals.
- Workers: embedded get plaintext; Kafka/HTTP workers get ciphertext unless they are configured with
  the same `workflow.encryption.*` keys (`worker-api` opens inputs and re-seals outputs).
- **Refused at load** when computed from a sensitive variable: `searchAttributes`, `correlationExpression`,
  `lockKey`/`processLock.key`, `onEvent.correlationKey`, `awaitReply` correlation, `startPolicy.key`, a
  trigger's `businessKey`.
- `LLM_CALL` / `AGENT_CALL` reading a sensitive variable are refused unless `llm.allowSensitiveVariables: true`
  / `agent.allowSensitiveVariables: true`. Update results that read them need `allowSensitiveVariables: true`.

## Webhooks, events and Kafka

- Git-import webhooks (`/workflow/webhooks/*`, forms/rules too) need `workflow.git-import.webhook-secret`
  (or a Spring Security chain / `allow-anonymous`), else 401 (2.28).
- Webhook gateway (`webhook-gateway-standalone-app`): `workflow.webhooks.endpoints.<name>.{provider: github|generic, destination, secret, …}`,
  `transport: kafka|rest`; with `rest` set `workflow.webhooks.engine.api-key` (`ENGINE_API_KEY`) = the engine's api key.
- Kafka clients of the inbound-event consumer and the gateway inherit `spring.kafka.security.*`,
  `spring.kafka.ssl.*`, `spring.kafka.properties.*` and the binder configuration; per-client overrides
  `workflow.events.consumer.properties.*`, `workflow.webhooks.kafka.properties.*`.
- Rules gRPC API is off by default (`RULES_GRPC_ENABLED=false`); use `rules.source=rest`.
