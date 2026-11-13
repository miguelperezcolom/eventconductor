---
title: "4 · Calling the hotel"
description: Book each room in the hotel's own system over HTTP, without writing a worker — with a connection, an idempotency key and an HTTP compensation.
---

Until now the rooms were booked by our own worker. In real life the hotel has an API. An `HTTP_CALL` step calls it **without writing a worker**: the engine renders the request from the process and maps the response back.

The workshop app serves a **fake hotel API** at `/fake/hotel/bookings` (see `FakeHotelApi.java`): it books a room, refuses a penthouse with `409`, and honours `Idempotency-Key` — the same key twice returns the same booking.

## The connection

Definitions never hold URLs or credentials. They name a **connection**, configured by the operator:

```properties
workflow.http.connections.hotel.base-url=http://localhost:${server.port}/fake/hotel
workflow.http.connections.hotel.read-timeout=PT5S
```

## The definition

`04-rooms-booking-http.yaml`:

```yaml
id: rooms-booking-http
name: Book every room at the hotel
steps:
  - id: start
    type: START
    name: Booking received

  - id: rooms
    type: HTTP_CALL
    name: Book every room at the hotel
    preconditionStepId: start
    compensable: true
    compensationStepId: cancel-room          # undoes each room that was booked
    retries: 3
    timeout: PT15S
    forEach:                                 # one call per room, each with its own retries
      items: rooms
      as: room
      label: "'Room ' + room.number"
      onIterationFailure: compensateAll
      output: roomBookings
      outputElement: roomBookingId
    http:
      connection: hotel                      # base URL and credentials live in configuration
      method: POST
      path: /bookings
      headers:
        # The same key on every attempt — and on a retry from a step, a restart, or the same
        # booking sent twice — so the hotel books each room once.
        Idempotency-Key: "${businessKey}-${room.number}"
      body:
        externalRef: "${businessKey}-${room.number}"
        number: "${room.number}"
        type: "${room.type}"
      output:
        roomBookingId: "body.id"

  - id: done
    type: END
    name: Done
    preconditionStepId: rooms

  - id: cancel-room                          # the compensation, also over HTTP
    type: HTTP_CALL
    name: Cancel the room at the hotel
    retries: 5
    http:
      connection: hotel
      method: DELETE
      path: "/bookings/by-ref/${businessKey}-${room.number}"
      successStatus: [200, 204, 404]         # already gone is fine: cancelling is idempotent too
```

- **The `forEach` sits on the `HTTP_CALL` itself**: one call per room, each with its own retries, and each iteration's templates see its `room` — `${room.number}`, `${room.type}`.
- **The idempotency key is built from business data** — the booking's business key and the room number. Any repeat of the same request, for whatever reason, carries the same key, and the hotel books the room once. See [Not creating duplicates in the target system](/guides/http-calls/#not-creating-duplicates-in-the-target-system).
- **The compensation is idempotent too**: it cancels by the same business reference and treats `404` as done.

:::caution[An HTTP call inside an inline body]
Inside an inline `PROCESS` body (module 2's `steps:`), each branch is a child process: there, `${businessKey}` is the *child's* generated key and the branch's `room` arrives as JSON text, so `${room.number}` does not render. For a call per item that needs the item's fields, put the `forEach` on the `HTTP_CALL` step, as above.
:::

## Run it

```bash
scripts/start.sh rooms-booking-http BOOKING-4 '{"guest": "Carol", "rooms": [
  {"number": "101", "type": "double"}, {"number": "102", "type": "double"},
  {"number": "103", "type": "double"}]}'
curl -s http://localhost:8095/fake/hotel/bookings
```

The hotel now holds three bookings, `externalRef` and `idempotencyKey` `BOOKING-4-101`, `-102`, `-103`. Now with the penthouse:

```bash
scripts/start.sh rooms-booking-http BOOKING-5 '{"guest": "Dan", "rooms": [
  {"number": "101", "type": "double"}, {"number": "102", "type": "double"},
  {"number": "900", "type": "penthouse"}]}'
```

![Over HTTP: Room 900 answered 409, the two others were booked and then cancelled](/workshop/04-http-iterations.jpg)

The hotel answered `409` for the penthouse — a 4xx is not retried, a 409 will not fix itself — so the step fails and the two rooms it had booked are cancelled with `DELETE` calls. `curl /fake/hotel/bookings` again: nothing of BOOKING-5 is left at the hotel.

## Check

- [ ] BOOKING-4 completed; the hotel holds exactly one booking per room.
- [ ] BOOKING-5 is **Compensated**; the hotel holds nothing for it.
