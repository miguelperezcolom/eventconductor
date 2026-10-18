---
title: Starting a Workflow from a Webhook
description: The webhook gateway — GitHub or any HMAC-signed sender — verifies each delivery, turns it into a CloudEvent and publishes it on a destination, where event triggers start workflows. The engine never learns what a webhook is.
---

A webhook is a domain event that arrives over HTTP, signed by its sender. The **webhook gateway** turns it into the same thing every other domain event is to the engine — a [CloudEvent on a destination](/guides/domain-event-triggers/) — so an [event trigger](/guides/domain-event-triggers/#event-triggers) starts the workflow, an [`onEvent` wait](/guides/domain-event-triggers/#waiting-for-a-domain-event) picks it up, each delivery is used once, and the engine holds no endpoint exposed to the internet and no sender's secret.

```
GitHub ──POST /webhooks/github──▶ webhook-gateway ──CloudEvent──▶ topic github-events ──▶ engine: triggers, onEvent
                                  (signature, mapping)             (or POST /workflow/api/events/github)
```

## The gateway

`webhook-gateway-standalone-app` (image `webhook-gateway-standalone-app`) exposes `POST /webhooks/{endpoint}` for every endpoint it is configured with. Each endpoint names its **provider** (how the delivery is read), its **destination** and its **secret**:

```yaml
workflow:
  webhooks:
    transport: kafka                         # or rest, see below
    endpoints:
      github:                                # POST /webhooks/github
        provider: github
        destination: github                  # a logical destination, mapped to its topic below
        secret: ${GITHUB_WEBHOOK_SECRET}     # the secret given to GitHub
      partner:                               # POST /webhooks/partner
        provider: generic
        destination: partner-events
        secret: ${PARTNER_WEBHOOK_SECRET}    # optional: HMAC-SHA256 of the body, hex, in X-Signature-256
        type: com.partner.order.changed      # fixed type; with a secret, a CE-Type header cannot override it
  events:
    destinations:
      github: { topic: github-events }       # the same keys the engine uses
      partner-events: { topic: partner-events }
```

As environment variables: `WORKFLOW_WEBHOOKS_ENDPOINTS_GITHUB_PROVIDER=github`, `WORKFLOW_WEBHOOKS_ENDPOINTS_GITHUB_DESTINATION=github`, `WORKFLOW_WEBHOOKS_ENDPOINTS_GITHUB_SECRET=…`, `WORKFLOW_EVENTS_DESTINATIONS_GITHUB_TOPIC=github-events`. An environment variable name cannot hold a `-`, so a destination or endpoint configured this way is named without one (`github`, `partnerorders`); a hyphenated name (`partner-events`) goes in YAML or `SPRING_APPLICATION_JSON`.

The answer is **202** with the event's id, type and destination once the event is on the topic (or accepted by the engine); **401** for a missing or bad signature; **404** for an endpoint that does not exist; **400** for a delivery the provider cannot read; **5xx** when it could not be published — the sender may redeliver, and because the delivery id becomes the CloudEvents id, a redelivery is the same event to the engine and is used once.

### GitHub

| GitHub | CloudEvent |
|---|---|
| `X-Hub-Signature-256` | verified: `sha256=` + HMAC-SHA256 of the body under the endpoint's `secret`, which is required |
| `X-GitHub-Delivery` | `id` — a redelivery carries the same one |
| `X-GitHub-Event` + the payload's `action` | `type`: `<typePrefix><event>[.<action>]` — `com.github.push`, `com.github.pull_request.opened`, `com.github.issues.closed` (`typePrefix` defaults to `com.github.`) |
| `repository.full_name` | `subject`, and the record key — the default correlation key of an `onEvent` wait |
| the body | `data` (`application/json`) |
| | `source`: the endpoint's `source`, default its name; extensions `githubevent`, `githubaction`, `repository`, `sender` |

In GitHub: repository (or organization) → Settings → Webhooks → Add webhook: Payload URL `https://<gateway>/webhooks/github`, Content type `application/json`, the secret, and the events. GitHub's `ping` arrives as `com.github.ping`.

### Any sender

The `generic` provider reads the attributes from headers the endpoint names — CloudEvents' own binary headers by default — or from its configuration:

| Setting | Default | Meaning |
|---|---|---|
| `secret` | — | When set, `signatureHeader` must carry the hex HMAC-SHA256 of the body (`sha256=` prefix tolerated); without one, the endpoint accepts what it is sent — keep it behind something that checks |
| `signatureHeader` | `X-Signature-256` | |
| `typeHeader` / `type` | `CE-Type` / — | The type from the header, else the fixed one; one of the two is needed. On an endpoint with a `secret`, a fixed `type` always wins: the header is ignored |
| `idHeader` | `CE-Id` | The id from the header, else a random UUID — then every delivery is a new event |
| `subjectHeader` | `CE-Subject` | The subject, also the record key |
| `source` | the endpoint's name | |

The body is the payload as it came, with its `Content-Type`.

### What the signature covers

**Only the body is signed** — for GitHub that is GitHub's own scheme, and the generic provider does the same. Headers are not: whoever holds one signed delivery can resend its body with other headers. So:

- On a **generic** endpoint with a `secret`, prefer a fixed `type`: then the event's type comes from the configuration, never from a header, and a replayed body cannot be relabelled as another kind of event (a fixed `type` on a signed endpoint ignores `CE-Type`). Without a fixed type, the type header is trusted as sent.
- The **id** comes from an unsigned header (`X-GitHub-Delivery`, `CE-Id`): a replay that keeps it is the same event and is used once, but one that changes it is a new event to the engine. Where that matters, put what identifies the business fact in the trigger's `businessKey` (taken from the signed body), which collapses a second start for the same key.
**Body size.** A body over `workflow.webhooks.max-body-bytes` (default `26214400`, 25 MB — GitHub's own payload cap) is refused with `413` before it is read whole, whether the sender declares its length or streams it chunked. Lower it for senders known to post small bodies.

## Transport

- **`kafka`** (default): the event is written on the destination's topic in binary mode — `ce_*` headers, the payload as the value, the subject as the key — exactly what `PUBLISH_EVENT` writes and the engine's inbound consumer reads. Brokers: `workflow.webhooks.kafka.bootstrap-servers`, else `spring.cloud.stream.kafka.binder.brokers`, else `spring.kafka.bootstrap-servers`. Authentication and TLS come from the application's Kafka settings — `spring.kafka.security.protocol`, `spring.kafka.ssl.*`, `spring.kafka.properties.*` (e.g. `sasl.mechanism`, `sasl.jaas.config`), the binder's `configuration` map — and `workflow.webhooks.kafka.properties.*` overrides them for the gateway alone; see [Kafka client security](/reference/configuration/#kafka-client-security-sasl-tls). The send is awaited (`acks=all`), so the 202 means the broker has it.
- **`rest`**: the event is handed to the engine's `POST /workflow/api/events/{destination}` as a structured CloudEvent (`workflow.webhooks.engine.url`, `api-key`), for embedded-mode deployments without a broker, or when the gateway should hold no broker credentials. It authenticates with `X-Api-Key` only, so it targets an **embedded engine with `workflow.message-api.api-key` set**, the same value as the gateway's `workflow.webhooks.engine.api-key` (`ENGINE_API_KEY`) — not the standalone orchestrator, whose Spring Security requires HTTP Basic. Since 2.27.0 the engine's REST API (`/workflow/api/**`) refuses anonymous requests: send an `X-Api-Key` matching `workflow.message-api.api-key`, or authenticate through the application's Spring Security; only `workflow.message-api.allow-anonymous=true` opens it — see [HTTP security](/reference/configuration/#http-security). When the engine cannot be reached or refuses the event, the sender gets a generic `502` to redeliver; the engine's address and its answer are only logged.

## Starting a workflow on it

Nothing new on the engine's side: a trigger on the destination, in the definition or in an [`events.yaml`](/guides/domain-event-triggers/#eventsyaml-triggers-outside-the-definition), or [created from the UI](/guides/domain-event-triggers/#triggers-created-from-the-ui-the-rest-api-and-mcp).

```yaml
# events.yaml
- id: on-pull-request
  destination: github
  type: com.github.pull_request.*
  workflow: review-pull-request
  filter: "data.pull_request.draft == false"
  businessKey: "review-pull-request-${data.repository.full_name}-${data.number}"   # unique across workflows: name the workflow
  variables:
    repository: "${data.repository.full_name}"
    number: "${data.number}"
    action: "${extensions.githubaction}"
```

## Observability

- Counter `eventconductor.webhooks.received{endpoint, outcome}` — `published`, `unauthorized`, `rejected`, `unknown`, `error`. A configured endpoint is tagged with its name; every delivery to an endpoint that is not configured is counted under `endpoint="_unknown"` (outcome `unknown`), whatever URL was called, so made-up URLs cannot grow the number of series.
- The engine's side as for any event: **Workflow → Received events**, `eventconductor.events.received{destination, outcome}`.

## Embedding

The gateway is also a library, `io.mateu.workflow:webhook-gateway`: on the classpath of any Spring Boot web application it maps the endpoints (`workflow.webhooks.enabled=false` leaves them out) and publishes through Kafka when `spring-kafka` is present, or through the engine's REST with `transport: rest`. It depends on nothing of the engine.
