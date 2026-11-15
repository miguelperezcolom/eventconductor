---
title: Secure by default
description: What EventConductor refuses until you say otherwise — anonymous callers, secrets from the environment, credentials to arbitrary hosts, unverified tokens, sensitive data without keys — and the switches that open each one.
---

Since 2.27 and 2.28 an EventConductor engine opens nothing to a caller it cannot identify, and a definition
cannot reach a secret, a credential or a host the operator did not hand it. This page lists each default, what
it refuses, and the switch that changes it. The switches that open things are for a laptop or an isolated
network; each says so.

<div style="max-width: 860px; margin: 1.5rem 0;">
  <iframe src="https://www.youtube-nocookie.com/embed/JJJTiJYDISY" title="EventConductor · 14. Secure and observable by default" loading="lazy" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen style="width: 100%; aspect-ratio: 16 / 9; border: 0; display: block; border-radius: 12px;"></iframe>
  <p style="font-size: 0.9em; margin-top: 0.5rem;">Chapter 14 of <em>EventConductor in N minutes</em> (2:39) · English, with English and Spanish subtitles</p>
</div>

## Nobody anonymous: the engine's REST API

`/workflow/api/**` — processes, messages, events, triggers, schedules, sync invocations, updates, operations,
incidents, batch operations — lets a request in when **one** of these holds:

1. `workflow.message-api.api-key` is set and the request sends it as `X-Api-Key`;
2. no key is set and the application has a Spring Security filter chain (any chain counts, even one that permits
   everything);
3. no key, no chain, and `workflow.message-api.allow-anonymous=true`.

Otherwise the answer is **401**. This holds in **embedded mode** too: an application that embeds the engine
without Spring Security and without a key refuses every API call (until 2.27.0 it answered anybody). The
startup log says which rule applies (`EngineApiAccess`).

```properties
# Choose one.
workflow.message-api.api-key=${ENGINE_API_KEY}          # callers send X-Api-Key
# ...or add Spring Security (any SecurityFilterChain bean)
# ...or, on a laptop only:
workflow.message-api.allow-anonymous=true
```

The standalone apps (`apps/*-standalone-app`) run Spring Security with HTTP Basic (`SECURITY_USER`,
`SECURITY_PASSWORD`); with `SECURITY_ENABLED=false` their filter chain permits everything, so their API is open —
set an api key if they are reachable.

## Nobody anonymous: the consoles and MCP

The admin UI (`/_workflow`, `/_workflow-admin`, `/_forms…`, `/_rules`, and any Mateu UI of the application that
mounts the engine's menus), the MCP endpoints (Mateu's `/mateu/mcp` and a Spring AI MCP server exposing the
engine's tools), `GET /rules[/{id}]` and the task-project downloads follow the same rule **without** the api
key — a browser cannot send one: they need a Spring Security filter chain or `allow-anonymous=true`, otherwise
**403** with a page saying what to do. The application's own endpoints, the actuator and static resources are
not touched by this check.

- `workflow.ui.enabled=false` does not mount the admin UI at all (404).
- `spring.ai.mcp.server.enabled=false` switches an MCP server off. MCP is opt-in in the first place: it is a
  dependency (`spring-ai-starter-mcp-server-webmvc`) plus a `ToolCallbackProvider` publishing the engine's
  `McpTools`, which the standalone orchestrator has and an embedded application adds itself.

`allow-anonymous=true` opens the API **and** the consoles. It exists for a laptop, a workshop or a test suite:
never set it on anything reachable from a network.

## Tokens are verified by someone, or not trusted

The engine and the forms console can read the caller from a bearer token that something in front — an API
gateway, a service mesh — has already verified. They do not verify it themselves, so they read it only with
`workflow.security.trust-forwarded-token=true` (default `false`); the claims are named by
`workflow.security.claims.*` (Keycloak's by default). An identity the application authenticated itself (Spring
Security) always wins over a forwarded one.

Set it only behind a gateway that verifies every token. Where nothing does, the claims are whatever the caller
typed: on a laptop it lets a script act as "a manager" without a login server (the workshop and the series app
do exactly that, and say so), and nowhere else.

## Definitions cannot pick their own secrets

A definition is written by whoever can write a definition; the credentials belong to the operator.

- **`${secret:NAME}` resolves only from `workflow.http.secrets.<NAME>`** (2.27.0). Before, a name not listed
  there fell back to any environment variable or Spring property — a definition could send a database password
  to a host of its choosing. `workflow.http.secrets-from-environment=true` restores the old lookup; don't.
- **Literal credentials in a definition fail validation**, as does `${secret:}` outside an inline `auth` block,
  and an LLM key in a definition: LLM keys live only in `workflow.llm.connections.<name>.api-key`.
- **Credentials are bound to hosts** (2.28.0). A named auth profile is applied through a `connection` (bound to
  its base URL), or on an absolute `url` only when the host is in `workflow.http.auth.<name>.hosts` — or, with no
  `hosts`, when `workflow.http.allowed-hosts` is set. Otherwise the step fails, not retryable, with
  `HTTP_FORBIDDEN_AUTH`. `workflow.http.credentials-to-any-host=true` restores the earlier behaviour.
- **Internal addresses are refused for absolute URLs** (`HTTP_FORBIDDEN_HOST`): reach an internal service
  through a `connection`.

```properties
workflow.http.connections.payments.base-url=https://payments.example.com
workflow.http.connections.payments.auth=payments-key
workflow.http.auth.payments-key.type=api-key
workflow.http.auth.payments-key.header=X-Api-Key
workflow.http.auth.payments-key.value=${PAYMENTS_API_KEY}
workflow.http.secrets.PARTNER_TOKEN=${PARTNER_TOKEN}    # the only ${secret:PARTNER_TOKEN} there is
```

See [HTTP calls](/guides/http-calls/) for connections, auth profiles and inline `auth` blocks.

## Webhooks are signed

- **Git-import webhooks** (`/workflow/webhooks/*`, and the forms, rules and tasks ones) make the engine clone a
  repository and replace definitions. With repositories configured and no `workflow.git-import.webhook-secret`
  (or `forms.`/`rules.`/`tasks.git-import.webhook-secret`), they answer **401** unless the application has a
  Spring Security filter chain or `allow-anonymous=true` (2.28.0).
- **The webhook gateway** checks each delivery's HMAC signature: required for GitHub, and for a `generic`
  endpoint whenever it has a `secret` — an endpoint without one accepts what it is sent, so keep it behind
  something that checks. See [Starting a workflow from a webhook](/guides/webhooks/).

## Sensitive variables need keys

Encryption is declared per definition: `sensitiveVariables: [cardNumber, "patient.*"]` (and `sensitiveOutputs:
true` on a step) marks what is sensitive. What is secure by default is what happens then (2.32.0):

- a definition that declares sensitive variables is **refused without encryption keys**
  (`workflow.encryption.keys.<id>`, the base64 of 32 bytes, and `workflow.encryption.active-key`), and an engine
  whose store already holds encrypted values does not start without them;
- the values are sealed with AES-256-GCM in process and step rows, the outbox, Kafka, the HTTP task queue,
  forms, batch parameters and rendered HTTP/LLM requests, shown as `••••` in the console, REST and MCP, and
  scrubbed from logs;
- revealing one needs a role in `workflow.security.sensitive.reveal-roles` (default: nobody) and a reason, and is
  audited; an api key alone never reveals;
- an `LLM_CALL` or `AGENT` step that reads one is refused at load unless it opts in with
  `allowSensitiveVariables: true`.

See [Sensitive data and encryption](/guides/sensitive-data-and-encryption/).

## Observability is opt-in

Nothing is exported until the host application asks: metrics need a `MeterRegistry` (Actuator + the Prometheus
registry gives `/actuator/prometheus`), and tracing is **off** — `management.tracing.sampling.probability`
defaults to `0.0` in the standalone apps. See [Observability](/reference/observability/).

## The laptop switches, in one place

| Setting | Default | What it opens | Where it belongs |
|---|---|---|---|
| `workflow.message-api.allow-anonymous` | `false` | the REST API, the consoles, MCP | a laptop, a workshop, a test suite |
| `workflow.security.trust-forwarded-token` | `false` | the caller read from an unverified bearer token | behind a gateway that verifies tokens; a laptop |
| `workflow.http.secrets-from-environment` | `false` | `${secret:NAME}` from any environment variable or property | nowhere new |
| `workflow.http.credentials-to-any-host` | `false` | credentials sent to absolute URLs outside `hosts` / `allowed-hosts` | nowhere new |
| `SECURITY_ENABLED` (standalone apps) | `true` | a permit-all filter chain: the API and consoles of that app | a laptop, or with an api key |

The repository's demos — `examples/workshop-hotel-booking` and `examples/video-series` — set
`allow-anonymous` and `trust-forwarded-token` so that they run with one command and no login server, and say so
next to each line. Copy their definitions, not those two lines.
