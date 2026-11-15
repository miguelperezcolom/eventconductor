---
title: "4 · Calling the hotel"
description: Book each room in the hotel's own system over HTTP, without writing a worker — with a connection, an idempotency key, the hotel's alternatives for a refused room, and an HTTP compensation when the guest cancels.
---

Until now the rooms were booked by our own worker. In real life the hotel has an API. An `HTTP_CALL` step calls it **without writing a worker**: the engine renders the request from the process and maps the response back.

The workshop app serves a **fake hotel API** under `/fake/hotel` (see `FakeHotelApi.java`): `/bookings` books a room, refuses a penthouse with `409`, and honours `Idempotency-Key` — the same key twice returns the same booking; `/alternatives?booking=…` offers, for each room of a booking it had to refuse, another one — the suite next door.

## The connection

Definitions never hold URLs or credentials. They name a **connection**, configured by the operator:

```properties
workflow.http.connections.hotel.base-url=http://localhost:${server.port}/fake/hotel
workflow.http.connections.hotel.read-timeout=PT5S
```

## The definition

`04-rooms-booking-http.ec.yaml`:

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
    compensationStepId: cancel-room          # how to undo a room that was booked — if the guest cancels
    retries: 3
    timeout: PT15S
    forEach:                                 # one call per room, each with its own retries
      items: rooms
      as: room
      label: "'Room ' + room.number"
      onIterationFailure: continue           # a refused room stops nothing: keep what was booked
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

  - id: all-booked
    type: CHOICE
    name: Every room booked?
    preconditionStepId: rooms

  - id: booked
    type: END
    name: Booked
    preconditionStepId: all-booked           # the default: every room was booked

  - id: find-alternatives                    # ask the hotel what it can offer instead
    type: HTTP_CALL
    name: Find alternatives at the hotel
    retries: 3
    preconditions:
      - stepId: all-booked
        expression: "roomBookings =~ '.*FAILED.*'"
    http:
      connection: hotel
      method: GET
      path: /alternatives
      query: { booking: "${businessKey}" }
      output:
        alternatives: "body"                 # [{"number": "901", "type": "suite", "instead": "900"}]

  - id: ask-guest
    type: ACTION
    name: Ask the guest
    retries: 3
    preconditionStepId: find-alternatives

  - id: wait-for-guest
    type: WAIT_FOR_MESSAGE
    name: Wait for the guest's answer
    preconditionStepId: ask-guest
    messageName: guest-answer                # {"answer": "accept" | "keep" | "cancel"}
    correlationExpression: businessKey

  - id: guest-decision
    type: CHOICE
    name: What did the guest decide?
    preconditionStepId: wait-for-guest

  - id: book-alternatives                    # the guest accepts: book what the hotel offered
    type: HTTP_CALL
    name: Book the alternatives
    compensable: true
    compensationStepId: cancel-room
    retries: 3
    timeout: PT15S
    preconditions:
      - stepId: guest-decision
        expression: "answer == 'accept'"
    forEach:
      items: alternatives
      as: room                               # the same name, so cancel-room undoes these too
      label: "'Room ' + room.number"
      output: alternativeBookings
      outputElement: roomBookingId
    http:
      connection: hotel
      method: POST
      path: /bookings
      headers:
        Idempotency-Key: "${businessKey}-${room.number}"
      body:
        externalRef: "${businessKey}-${room.number}"
        number: "${room.number}"
        type: "${room.type}"
      output:
        roomBookingId: "body.id"

  - id: booked-with-alternatives
    type: END
    name: Booked with alternatives
    preconditionStepId: book-alternatives

  - id: cancelled-by-guest                   # the guest cancels: undo every room that was booked
    type: END
    name: Cancelled by the guest
    compensate: true
    preconditions:
      - stepId: guest-decision
        expression: "answer == 'cancel'"

  - id: kept
    type: END
    name: Booked (rooms kept)
    preconditionStepId: guest-decision       # the default: keep the rooms that were booked

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
- **A refused room is kept apart, not undone**: `onIterationFailure: continue`, as in module 3. `find-alternatives` asks the hotel what it can offer instead (a `GET` with a `query`, its JSON body mapped whole into `alternatives`), and `ask-guest` — module 3's worker — tells the guest.
- **The guest's answer decides**: `accept` books the alternatives — a second `forEach` over `alternatives`, with the item named `room` again so `cancel-room` can undo those too — `keep` keeps what was booked, and `cancel` reaches the `END` with `compensate: true`.
- **The compensation is idempotent too**: it cancels by the same business reference and treats `404` as done.

:::note[An HTTP call inside an inline body]
The same call works inside an inline `PROCESS` body (module 2's `steps:`), when each room needs several steps: each branch is a child process, but its templates read `${room.number}` off the branch's room and `${businessKey}` as the booking's key, so the idempotency key is the same. For one call per room, the `forEach` on the `HTTP_CALL` step, as above, is simpler.
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
curl -s 'http://localhost:8095/fake/hotel/alternatives?booking=BOOKING-5'
```

The hotel answered `409` for the penthouse — a 4xx is not retried, a 409 will not fix itself — so that room is set aside; the other two stay booked. The hotel offers `[{"number":"901","instead":"900","type":"suite"}]`, and the process waits for the guest. Accept it:

```bash
scripts/answer.sh BOOKING-5 accept
curl -s http://localhost:8095/fake/hotel/bookings
```

The suite is booked (`BOOKING-5-901`) and the process ends *Booked with alternatives*:

![Over HTTP: Room 900 answered 409 and Room 101 and 102 stayed booked; the hotel's alternative was proposed, the guest accepted, the suite was booked (1/1) and the process ended Booked with alternatives — nothing cancelled](/workshop/04-http-iterations.jpg)

Start BOOKING-6 the same way and answer `cancel`: the two rooms it had booked are cancelled with `DELETE` calls — `curl /fake/hotel/bookings` holds nothing of BOOKING-6 — and the process ends **Compensated**.

## Check

- [ ] BOOKING-4 completed; the hotel holds exactly one booking per room.
- [ ] BOOKING-5 waited for the guest; after `accept`, the hotel holds 101, 102 and the suite 901.
- [ ] BOOKING-6, cancelled by the guest, is **Compensated**; the hotel holds nothing for it.
