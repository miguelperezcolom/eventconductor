---
title: Publishing Domain Events
description: The PUBLISH_EVENT step — publish a domain event from the process's state, through a built-in task, without writing a worker.
---

`PUBLISH_EVENT` publishes a domain event for other services — an inbox, analytics, another bounded context — from the process's state. You write no worker: the step is served by the built-in task **`publish-event@1`** (module `worker-events`), which ships inside the orchestrator and inside `worker-embedded`.

```yaml
- id: announce
  type: PUBLISH_EVENT
  name: Booking confirmed
  preconditionStepId: confirm
  event:
    destination: bookings                  # a logical name, mapped to a topic by configuration
    type: com.acme.booking.confirmed
    key: "${bookingId}"                    # optional; default the business key, else the process id
    payload:                               # a structured template — or payloadTemplate (text) or payloadVariables: [..]
      bookingId: "${bookingId}"
      total: "${total * 1}"
    format: binary                         # optional: binary (default) | structured | plain
```

The payload is a [payload template](/guides/payload-templates/); `payloadVariables` makes an object of the listed variables; none of the three publishes `{}`.

## Guarantees

The engine renders and checks the event when the step starts — an unknown destination, a template that does not render or an oversized payload fails the step at once — and dispatches it as a task: the task request rides the **outbox in the same transaction as the step's state**, with the event in task variable `__event`, so it survives a crash or a broker outage like every transition. The built-in worker sends the event to its destination and then completes the step — the step completes **after** the event was sent. Delivery is at-least-once; the event's `id` is the step execution id — stable across redeliveries of the task — so consumers deduplicate on it.

Being a task, the step has what every worker step has: `timeout` (and `workflow.default-step-timeout-ms`), `retries`, incidents. The worker fails it with `EVENT_DESTINATION` (the destination has no topic in the worker's configuration — final), `EVENT_IO` (the broker refused the event — retried) or `EVENT_REQUEST` (a malformed task — final). In kafka mode the task goes to topic **`event-publications`** (a step's `topic` overrides), served by the orchestrator itself; `workflow.events.worker.enabled=false` turns the built-in worker off.

## Destinations are configuration

A definition names a **destination**, never a topic — so a definition imported from git cannot write into the engine's own topics:

```yaml
workflow:
  events:
    destinations:
      bookings: { topic: booking-events }
      audit:    { topic: audit-events, format: plain }
    max-payload-bytes: 262144
```

An unknown destination fails the step (in kafka mode, and in embedded mode once any destination is configured).

## Delivery

**Kafka mode** — the built-in worker sends the event to the destination's topic (`workflow.events.destinations.<name>.topic`/`format` in the worker's configuration — in the orchestrator, the same map the engine checks the step against), keyed by `key`, synchronously (a refused send fails the attempt with `EVENT_IO`, and `retries` send it again). As a **CloudEvent 1.0**:

- `binary` (default): the payload is the record value; `ce_specversion`, `ce_id`, `ce_source` (`eventconductor/<definition>`), `ce_type`, `ce_subject` (business key), `ce_time`, `ce_processid`, `ce_stepid` are headers.
- `structured`: the record value is the whole event as JSON (`application/cloudevents+json`).
- `plain`: the payload alone.

**Embedded mode** — the built-in worker runs in-process (`worker-embedded`, or the `builtin-workers` aggregator) and hands the event to the `ExternalEventPublisher` bean, then to the loopback. The default publishes a Spring application event, `ExternalEventPublished`:

```java
@EventListener
void on(ExternalEventPublished published) {
    var event = published.event();   // destination, eventType, key, data (JSON), eventId, processId, …
}
```

An application with a broker provides its own `ExternalEventPublisher` bean (Kafka, RabbitMQ, SNS…); it is called by the worker after the step's transaction committed, and throwing fails the attempt, which the step's `retries` repeat. An application with a hand-written `EmbeddedTaskExecutor` instead of `worker-embedded` must serve `publish-event@1` too, or its `PUBLISH_EVENT` steps stay `PENDING`.

## Waiting for the answer

An event that is a request — someone else answers it later — can wait for its answer in the same step: give the `PUBLISH_EVENT` an `awaitReply` (`messageName`, `correlationExpression` and an optional `onEvent`, as on a `WAIT_FOR_MESSAGE`). The wait is armed in the transaction that dispatches the task, so an answer cannot come back before the step is waiting for it; the worker's report leaves the step waiting (as for an `HTTP_CALL`), the step completes when the answer is correlated, and its `timeout` and `onTimeoutStepId` cover the wait. See [Asynchronous Request/Response](/guides/request-response/#b-ec-asks-and-waits).

## Listening to events

The other direction — a workflow started by a domain event, or a `WAIT_FOR_MESSAGE` waiting for one — uses the same destinations and CloudEvents conventions: see [Starting on and waiting for domain events](/guides/domain-event-triggers/). In embedded mode an event published here is also received by this engine's own triggers and waits (`workflow.events.loopback`, default `true`).
