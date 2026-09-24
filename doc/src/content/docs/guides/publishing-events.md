---
title: Publishing Domain Events
description: The PUBLISH_EVENT step — publish a domain event from the process's state, through the outbox, without writing a worker.
---

`PUBLISH_EVENT` publishes a domain event for other services — an inbox, analytics, another bounded context — from the process's state. No worker is involved.

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

The event is written to the **outbox in the same transaction that completes the step**: it is published **if and only if** the step completed, and it survives a crash or a broker outage like every transition. Delivery is at-least-once; the event's `id` is the step execution id — stable across relay retries and redeliveries — so consumers deduplicate on it.

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

**Kafka mode** — the relay sends the event to the destination's topic, keyed by `key`, synchronously (a refused send stays in the outbox). As a **CloudEvent 1.0**:

- `binary` (default): the payload is the record value; `ce_specversion`, `ce_id`, `ce_source` (`eventconductor/<definition>`), `ce_type`, `ce_subject` (business key), `ce_time`, `ce_processid`, `ce_stepid` are headers.
- `structured`: the record value is the whole event as JSON (`application/cloudevents+json`).
- `plain`: the payload alone.

**Embedded mode** — the event is handed to the `ExternalEventPublisher` bean. The default publishes a Spring application event, `ExternalEventPublished`:

```java
@EventListener
void on(ExternalEventPublished published) {
    var event = published.event();   // destination, eventType, key, data (JSON), eventId, processId, …
}
```

An application with a broker provides its own `ExternalEventPublisher` bean (Kafka, RabbitMQ, SNS…); it is called after the step's transaction committed, and throwing leaves the event to be retried.
