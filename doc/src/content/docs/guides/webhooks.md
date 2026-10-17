---
title: Starting a Workflow from a Webhook
description: The webhook gateway — GitHub or any HMAC-signed sender — verifies each delivery, turns it into a CloudEvent and publishes it on a destination, where event triggers start workflows. The engine never learns what a webhook is.
---

A webhook is a domain event that arrives over HTTP, signed by its sender. The **webhook gateway** turns it into the same thing every other domain event is to the engine — a [CloudEvent on a destination](/guides/domain-event-triggers/) — so an [event trigger](/guides/domain-event-triggers/#event-triggers) starts the workflow, an [`onEvent` wait](/guides/domain-event-triggers/#waiting-for-a-domain-event) picks it up, each delivery is used once, and the engine holds no endpoint exposed to the internet and no sender's secret.

```
GitHub ──POST /webhooks/github──▶ webhook-gateway ──CloudEvent──▶ topic github-events ──▶ engine: triggers, onEvent
                                  (signature, mapping)             (or POST /workflow/api/events/github-events)
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
        destination: github-events
        secret: ${GITHUB_WEBHOOK_SECRET}     # the secret given to GitHub
      partner:                               # POST /webhooks/partner
        provider: generic
        destination: partner-events
        secret: ${PARTNER_WEBHOOK_SECRET}    # optional: HMAC-SHA256 of the body, hex, in X-Signature-256
        type: com.partner.order.changed      # when the sender puts no type in a CE-Type header
  events:
    destinations:
      github-events: { topic: github-events } # the same keys the engine uses
      partner-events: { topic: partner-events }
```

As environment variables: `WORKFLOW_WEBHOOKS_ENDPOINTS_GITHUB_PROVIDER=github`, `WORKFLOW_WEBHOOKS_ENDPOINTS_GITHUB_DESTINATION=github-events`, `WORKFLOW_WEBHOOKS_ENDPOINTS_GITHUB_SECRET=…`, `WORKFLOW_EVENTS_DESTINATIONS_GITHUB-EVENTS_TOPIC=github-events`.

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
| `typeHeader` / `type` | `CE-Type` / — | The type from the header, else the fixed one; one of the two is needed |
| `idHeader` | `CE-Id` | The id from the header, else a random UUID — then every delivery is a new event |
| `subjectHeader` | `CE-Subject` | The subject, also the record key |
| `source` | the endpoint's name | |

The body is the payload as it came, with its `Content-Type`.

## Transport

- **`kafka`** (default): the event is written on the destination's topic in binary mode — `ce_*` headers, the payload as the value, the subject as the key — exactly what `PUBLISH_EVENT` writes and the engine's inbound consumer reads. Brokers: `workflow.webhooks.kafka.bootstrap-servers`, else `spring.kafka.bootstrap-servers`. The send is awaited (`acks=all`), so the 202 means the broker has it.
- **`rest`**: the event is handed to the engine's `POST /workflow/api/events/{destination}` as a structured CloudEvent (`workflow.webhooks.engine.url`, `api-key`), for embedded-mode deployments without a broker, or when the gateway should hold no broker credentials.

## Starting a workflow on it

Nothing new on the engine's side: a trigger on the destination, in the definition or in an [`events.yaml`](/guides/domain-event-triggers/#eventsyaml-triggers-outside-the-definition), or [created from the UI](/guides/domain-event-triggers/#triggers-created-from-the-ui-the-rest-api-and-mcp).

```yaml
# events.yaml
- id: on-pull-request
  destination: github-events
  type: com.github.pull_request.*
  workflow: review-pull-request
  filter: "data.pull_request.draft == false"
  businessKey: "pr-${data.repository.full_name}-${data.number}"
  variables:
    repository: "${data.repository.full_name}"
    number: "${data.number}"
    action: "${extensions.githubaction}"
```

## Observability

- Counter `eventconductor.webhooks.received{endpoint, outcome}` — `published`, `unauthorized`, `rejected`, `unknown`, `error`.
- The engine's side as for any event: **Workflow → Received events**, `eventconductor.events.received{destination, outcome}`.

## Embedding

The gateway is also a library, `io.mateu.workflow:webhook-gateway`: on the classpath of any Spring Boot web application it maps the endpoints (`workflow.webhooks.enabled=false` leaves them out) and publishes through Kafka when `spring-kafka` is present, or through the engine's REST with `transport: rest`. It depends on nothing of the engine.
