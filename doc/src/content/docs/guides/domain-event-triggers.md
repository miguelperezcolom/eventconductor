---
title: Starting on and Waiting for Domain Events
description: Event triggers start a workflow when a domain event arrives — declared in the definition or in an events.yaml next to it; a WAIT_FOR_MESSAGE with onEvent waits for one. CloudEvents, the same destinations PUBLISH_EVENT publishes to, each event used once.
---

[`PUBLISH_EVENT`](/guides/publishing-events/) lets a process speak to other systems. This is the other direction: a definition can **listen** to the domain events of other systems — or of its own.

- An **event trigger** starts a process when an event arrives — like a [schedule](/guides/starting-a-process/#schedules-with-parameters), but on an event instead of a clock. It is declared in the definition, or in an [`events.yaml`](#eventsyaml-triggers-outside-the-definition) next to the definitions, as a schedule can live in `cron.yaml`.
- A **`WAIT_FOR_MESSAGE` with `onEvent`** waits until an event for *this* process arrives.

Events are **CloudEvents 1.0** and arrive on a **destination** — the logical name `workflow.events.destinations.<name>.topic` maps to a topic, the same names `PUBLISH_EVENT` uses. A definition never names a topic.

## Event triggers

```yaml
id: booking-onboarding
name: Booking onboarding
triggers:
  - id: on-booking-created               # optional; derived from the type when absent
    destination: bookings                # → workflow.events.destinations.bookings.topic
    type: com.acme.booking.created       # CloudEvents type; '*' is a wildcard: com.acme.booking.*
    filter: "data.channel == 'web'"      # optional JEXL over the event, must be true
    businessKey: "booking-onboarding-${data.bookingId}"   # optional; default derived from the event
    variables:                           # optional; default: data's top-level members
      bookingId: "${data.bookingId}"
      nights: "${data.nights}"
steps:
  - id: start
    type: START
    name: Start
  # …
```

| Field | Required | Meaning |
|---|---|---|
| `id` | no | The trigger's name, unique in the definition (letters, digits, `.`, `_`, `-`). A pause is kept under it and the default business key carries it. Absent: the type (`com.acme.booking.created`; a second trigger with the same type gets `-2`). |
| `destination` | yes | A logical destination. |
| `type` | yes | The CloudEvents type to match, exactly or with `*` wildcards. A lone `*` matches every event on the destination, including plain records without a type. |
| `filter` | no | A JEXL boolean over the event, without `${}`. Only `true` lets the event through; an expression that fails counts as false and is recorded as an error. |
| `businessKey` | no | A text template over the event. Absent: `<definition>-evt-<trigger>-<16 hex of SHA-256(source, id)>`, the same for every delivery of one event. A business key is **unique across workflows**, so put the workflow in it (`booking-onboarding-${data.bookingId}`) — see [below](#each-event-is-used-once). |
| `variables` | no | The started process's initial variables, each a [payload template](/guides/payload-templates/) over the event. Absent: every top-level member of `data` (when it is a JSON object). `{}`: none. A non-string value becomes its JSON text, as process variables are strings. |

A trigger starts a process **as the engine itself** (like cron: the definition's `requiredScopes`/`requiredRoles` do not apply), through the same door as cron, so sharding places it like any creation. Lifecycle, as for schedules:

- the definition must be **ACTIVE** — a DISABLED or ARCHIVED definition starts nothing;
- a **paused definition** still creates the process, already PAUSED;
- a **paused trigger** starts nothing, and resuming it does not replay what arrived meanwhile.

### `events.yaml`: triggers outside the definition

The same trigger, naming the workflow it starts, can live in an `events.yaml` (or `events.yml`) next to the definitions — `classpath:/workflows/events.yaml`, an imported directory or a git repository — so that *linking* an event to a workflow is not a change to the workflow. A bare list, or the list under a single `triggers:` key:

```yaml
- id: on-customer-updated              # optional, as in a definition
  destination: customers-cdc           # → workflow.events.destinations.customers-cdc.topic
  type: "*"                            # a CDC topic carries plain records: no type to match
  workflow: sync-customer              # the definition a matching event starts
  filter: "data.op == 'u'"
  businessKey: "sync-customer-${data.after.id}"
  variables:
    customerId: "${data.after.id}"
    email: "${data.after.email}"
```

`events.yaml` is never imported as a definition. Each import of a source replaces that source's entries (a removed entry stops firing; a deleted file clears them); an entry with an invalid destination, type or id, an unknown key, or an id declared twice for one workflow is logged and skipped, and the rest of the file applies. An entry naming a workflow the engine does not know **yet** is kept, with a warning (`workflow 'x' not known yet — the trigger applies once it is imported`): sources are imported one after another — the classpath first, then directories and git repositories — so a classpath `events.yaml` may name a workflow a git import brings later. Until that workflow exists the entry matches nothing and is not listed; once it is imported, the trigger applies. A file that cannot be read at all leaves the source's previous entries in place. Entries live in memory with `workflow.persistence=memory` and in the `event_trigger` table with `jpa` (migration V40), so every pod matches events against the same set. The engine and the Maven plugin validate the file against `events-schema.json`, and both IDE plugins do too.

A trigger declared in a file is the same kind of thing as one declared in the definition: same matching, same receipts, same receiver `trigger:<workflow>/<trigger>`, same pause by `<workflow>/<trigger>`, same business key default. Its `id` is unique within the workflow across both places — absent, it is derived from the type as in a definition (`-2` on a repeat). An entry whose id the definition already declares is a **duplicate**: listed with that status, never fires. The definition's own triggers come first, then its file entries by source and position; **Workflow → Event triggers** and the definition's **Triggers** tab show a **Source** column (`Definition`, `events.yaml (classpath|directory|git)`), and the REST and MCP views carry `origin`, `source`, `sourceLabel` and `declaredIn`. The ⚡ on the graph's START node marks only the triggers the definition itself declares.

Which to use: the definition, when the author of the workflow is the one who knows which event starts it — the trigger travels with the workflow. `events.yaml`, when the workflow is generic and the binding is a deployment or integration decision — a CDC stream, a partner's events — or when the people wiring events to workflows are not the ones writing the workflows.

### Triggers created from the UI, the REST API and MCP

A file is the optional, durable home of a trigger, not a precondition for having one. **Workflow → Event triggers** creates triggers (source `UI/API`): id, workflow, destination, type, filter, business key and variables — the same fields as an `events.yaml` entry, the id required. The same through `POST /workflow/api/triggers` (`{id, workflowId, destination, type, filter?, businessKey?, variables?}`), `PUT`/`DELETE /workflow/api/triggers/{workflowId}/{triggerId}` and `GET …/yaml`, and the MCP tools `createEventTrigger`, `updateEventTrigger`, `deleteEventTrigger`, `exportEventTriggerYaml`.

- **Any trigger** can be paused and resumed. **Only triggers created here** are edited and deleted here: one declared in a definition or an `events.yaml` is changed in its file — its page says where.
- A trigger created here is matched, receipted and keyed exactly like a declared one, and listed after the file-declared triggers of its workflow. Its id must be free within the workflow.
- **Promote it to git**: its page shows its `events.yaml` entry, id and workflow included (also `GET …/yaml`, `exportEventTriggerYaml`). Commit it next to the definitions: on the next import the file owns the trigger — the stored copy is deleted, the file's values take over, and its pause carries over. An entry removed from its file later is deleted with its pause, including one that started life in the UI. Imports never touch the triggers created here otherwise.

### What a filter and a template see

| Name | Value |
|---|---|
| `id`, `source`, `type`, `subject`, `time`, `datacontenttype` | The CloudEvents attributes |
| `key` | The Kafka record key (or the `key` given to the REST/MCP inject) |
| `destination` | The destination it arrived on |
| `data` | The payload, parsed as JSON — objects are maps, so `data.bookingId`, `data.guest.name` — or its text when it is not JSON |
| `extensions` | Any other attribute (`extensions.tenant`) |

Templates and filters run in the same sandbox as every other expression in a definition.

## Waiting for a domain event

```yaml
- id: wait-payment
  type: WAIT_FOR_MESSAGE
  name: Wait for the payment
  preconditionStepId: confirm
  messageName: payment-captured          # still required: the message the event becomes
  correlationExpression: bookingId       # over the process, as for any wait
  deadline: { date: "${checkinDate}", offset: -P1D }
  onTimeoutStepId: cancel-booking
  onEvent:
    destination: payments
    type: com.acme.payment.captured
    correlationKey: "${data.bookingId}"  # optional; default the record key, else the subject
    filter: "data.status == 'CAPTURED'"  # optional
    variables:                           # optional; default: data's top-level members
      paymentId: "${data.paymentId}"
```

An event matching `onEvent` is turned into the step's message — `messageName`, with the correlation key and variables it renders — and dispatched exactly like a message from the [message API](/guides/starting-a-process/). The step completes when that key equals its `correlationExpression` over the process. Everything a wait does stays as it is: the indexed subscription, `timeout`, `deadline`, `onTimeoutStepId`, compensation — and the message API can still complete the wait by `messageName` (handy in tests).

The default correlation key is the record key: a `PUBLISH_EVENT` keyed by the booking id (`key: "${bookingId}"`) is waited for with `correlationExpression: bookingId` and no `correlationKey` at all.

Like any message, an event that arrives before the step waits is **not buffered**: it correlates with nothing. It leaves no receipt either, so a redelivery by the producer once the step waits is correlated as usual.

## Each event is used once

The same event — the same CloudEvents `source` and `id` — **never starts two processes through one trigger, and is never correlated twice** (not even into a later wait of the same process on the same message), however often it is delivered and through whichever channel:

- After a trigger started a process or a wait got its message, the engine records a **receipt** for that receiver and event (`inbound_event_receipt` with `workflow.persistence=jpa`, in memory otherwise). A redelivery finds it and does nothing — it is reported as a duplicate. A wait's receipt is written when a waiting step actually takes the message, so an event that found no step waiting is not a duplicate on redelivery.
- The receipt is written after the start or the message was handed over, so a failure to hand over is retried. The short window in between (a crash, two copies at once) is closed by the **default business key**, derived from the event: the second creation collapses into the first.
- An author-given `businessKey` is honoured as given — so two *different* events that render the same key start one process.
- A business key is **unique across workflows**, not per workflow: a creation whose key any process already has — of whichever workflow — is ignored. Two triggers of *different* workflows that render the same key (`booking-${data.bookingId}` in both) start one process between them: the first wins. So put the workflow in the template — `booking-onboarding-${data.bookingId}`. The engine logs a WARN when triggers of different workflows share a `businessKey` template (on every import and when a trigger is created or edited), and a trigger whose rendered key another workflow's process already holds records an **error** receipt saying so instead of `STARTED` (best effort: a process created at the same moment, or on another shard, is not seen).
- Receipts are kept for `workflow.events.receipts.retention` (7 days); past it, a redelivery is a new event.
- An event whose filter or templates fail records an **error** receipt and that receiver does nothing; a later delivery is tried again.

## How events arrive

**Kafka mode.** The engine consumes the topics of the destinations that some trigger — in a definition or an `events.yaml` — or `onEvent` names, in one consumer group (`workflow.events.consumer.group`), so each event is used by one pod. The set is recomputed every `workflow.events.consumer.refresh-interval` — a git import that adds a trigger, in a definition or in `events.yaml`, is listened to without a restart. A new group starts at `latest`: turning a trigger on does not replay the topic's history. Records are read as CloudEvents in **binary** mode (`ce_*` headers — what `PUBLISH_EVENT` writes by default), **structured** mode (`application/cloudevents+json`), or as **plain** records (id `<topic>-<partition>-<offset>`, source `kafka://<topic>`, no type — only a `type: '*'` trigger matches them). In binary mode the data's content type is `ce_datacontenttype`, else the record's `contentType` or standard `content-type` header (any case). A destination named by a definition but configured without a topic is logged and not consumed. A record that can never be used — unreadable, without an id or source (blank counts), or failing the same way every time — is logged and skipped; only a failure to hand an event over (a broker down) is retried, after `workflow.events.consumer.retry-backoff`, until it goes through.

The consumer lives in its own module, `io.mateu.workflow:inbound-events-kafka`, outside the engine: the engine only matches CloudEvents on destinations and knows nothing of how they arrive. `orchestrator-standalone-app` includes it; an application of your own that runs the engine with `workflow.mode=kafka` adds the dependency to listen.

```yaml
workflow:
  events:
    destinations:
      bookings: { topic: booking-events }
      payments: { topic: payment-events }
    consumer:
      group: eventconductor-inbound-events
```

**Embedded mode.** Hand the engine an event yourself — call `InboundEventService.receive(new InboundEvent(...))`, or publish the `InboundEvent` as a Spring application event:

```java
applicationEventPublisher.publishEvent(new InboundEvent(
        "bookings", eventId, "pms", "com.acme.booking.created", null, null,
        bookingId, "application/json", json, Map.of()));
```

A `PUBLISH_EVENT` of this engine is **looped back** too (`workflow.events.loopback`, default `true`): one workflow's event can start or wake another, as it would through Kafka.

**Webhooks.** A sender that posts over HTTP — GitHub, or anything that signs with HMAC-SHA256 — goes through the [webhook gateway](/guides/webhooks/), which verifies the delivery and publishes it as a CloudEvent on a destination; the engine sees a domain event like any other.

**REST** (both modes) — for tests and for systems that cannot publish to Kafka:

```http
POST /workflow/api/events/bookings?key=B-1
Content-Type: application/json

{"specversion": "1.0", "id": "evt-123", "source": "pms", "type": "com.acme.booking.created",
 "data": {"bookingId": "B-1", "nights": 2, "channel": "web"}}
```

The answer says what the event did: `receipts` (each `STARTED` with its business key, `DELIVERED` with the message and key, or `ERROR` with the reason) and `duplicates`. Since 2.27.0 the engine's REST API (`/workflow/api/**`) refuses anonymous requests: send an `X-Api-Key` matching `workflow.message-api.api-key`, or authenticate through the application's Spring Security; only `workflow.message-api.allow-anonymous=true` opens it — see [HTTP security](/reference/configuration/#http-security). **MCP**: `publishInboundEvent` does the same.

## Operating triggers

- **Workflow → Event triggers** lists every trigger — workflow, destination and its topic, type, filter, business key, variables, source (`Definition`, the `events.yaml` it comes from, or `UI/API`), status — pauses or resumes the selected ones, and creates new ones; each trigger has its own page with its `events.yaml` entry. **Workflow → Received events** shows the latest receipts: what each event started, delivered, or why it could not be used. A definition's detail page has a **Triggers** tab.
- REST: `GET /workflow/api/triggers[?workflowId=]`, `GET /workflow/api/triggers/{workflowId}/{triggerId}`, `POST /workflow/api/triggers`, `PUT`/`DELETE …/{workflowId}/{triggerId}`, `GET …/yaml`, `POST …/pause`, `POST …/resume`, `GET /workflow/api/events/received[?workflowId=&limit=]`. Same access rule as the rest of `/workflow/api/**` ([HTTP security](/reference/configuration/#http-security)).
- MCP: `listEventTriggers`, `createEventTrigger`, `updateEventTrigger`, `deleteEventTrigger`, `exportEventTriggerYaml`, `pauseEventTrigger`, `resumeEventTrigger`, `listReceivedEvents`, `publishInboundEvent`.
- Metrics: `eventconductor.events.received{destination, outcome}` (`STARTED`, `DELIVERED`, `ERROR`, `DUPLICATE`), and the gauge `eventconductor.events.triggers{origin}` (`definition`, `events_file`, `api`): how many triggers can fire — a drop after an import means an entry was skipped, removed or taken over.
- The graph shows a ⚡ on the START node of a triggered workflow, and `⚡ <type> ← <destination>` above a wait fed by an event.

## Limits

- An event that matches nothing leaves no receipt (a busy topic would fill the table with noise); the REST/MCP inject says what happened.
- Waits are matched against each definition's **current** declaration. A process still running an older version whose `onEvent` changed since is completed through its `messageName` by the message API.
- A trigger created from the UI, the REST API or MCP lives in the engine's store until a file declares its key; the graph's ⚡ never shows it (nor an `events.yaml` entry).
