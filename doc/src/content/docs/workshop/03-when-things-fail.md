---
title: "3 · When things fail"
description: Retries, timeouts and compensation — and a booking that is all rooms or none.
---

The hotel's penthouse is always sold out. Book two doubles and the penthouse, and the third room fails. What should happen to the two that were booked?

For a booking, the answer is usually **all rooms or none**: if one cannot be booked, cancel the others. That is a **saga** — and in EventConductor it is three lines.

## The definition

`03-rooms-booking-safe.yaml` — the rooms flow of module 2, plus failure handling:

```yaml
id: rooms-booking-safe
name: Book every room (all or none)
steps:
  - id: start
    type: START
    name: Booking received

  - id: register-booking
    type: ACTION
    name: Register the booking
    preconditionStepId: start

  - id: rooms
    type: PROCESS
    name: Book every room
    preconditionStepId: register-booking
    compensable: true
    compensationStepId: cancel-room-booking  # undoes each room that was booked
    outputVariables: [roomBookingId]
    forEach:
      items: rooms
      as: room
      label: "'Room ' + room.number"
      onIterationFailure: compensateAll      # wait for every room; if one failed, undo the rest
      output: roomBookings
      outputElement: roomBookingId
    steps:
      - id: start
        type: START
        name: Start
      - id: create-room-booking
        type: ACTION
        name: Book the room
        preconditionStepId: start
        retries: 2                           # a technical failure is retried, with backoff
        timeout: PT30S                       # an answer that never comes is a failure too
      - id: end
        type: END
        name: End
        preconditionStepId: create-room-booking

  - id: notify-guest
    type: ACTION
    name: Tell the guest
    preconditionStepId: rooms

  - id: done
    type: END
    name: Done
    preconditionStepId: notify-guest

  - id: cancel-room-booking                  # the compensation: no preconditions, run by the rollback
    type: ACTION
    name: Cancel the room
```

| Line | What it does |
|---|---|
| `retries: 2` | A technical failure (an exception, a timeout) is retried twice, with exponential backoff |
| `timeout: PT30S` | A worker that never answers fails the attempt after 30 seconds — and that is retried too |
| `compensable` + `compensationStepId` | If the process fails later, every completed room is undone by `cancel-room-booking`, newest first |
| `onIterationFailure: compensateAll` | Let every room finish; if one failed, undo the rest and fail the step. The alternatives: `failFast` (the default — stop at the first failure) and `continue` (book what you can and report the rest) |

The handler says *why* it failed with a business code, and that the failure is final — retrying will not make the penthouse free:

```java
if ("penthouse".equals(input.room().type())) {
    throw new TaskFailure("SOLD_OUT", "Room " + input.room().number() + " (penthouse) is sold out", false);
}
```

The compensation handler receives the room and what booking it produced:

```java
public record CancelRoomBookingInput(Room room, String roomBookingId) {}
```

## Run it

```bash
scripts/start.sh rooms-booking-safe BOOKING-3 '{"guest": "Bob", "rooms": [
  {"number": "101", "type": "double"}, {"number": "102", "type": "double"},
  {"number": "900", "type": "penthouse"}]}'
```

![All rooms or none: rooms fails 2/3, Cancel the room runs 2/2](/workshop/03-compensated-graph.jpg)

The process ends **Compensated**. `rooms` is red with **2/3**; the dashed red edge leads to *Cancel the room*, drawn amber because it is a compensation, with **2/2**: one cancellation per room that had been booked. *Tell the guest* never ran. Open `rooms` to see which room failed:

![Rooms 101 and 102 completed, Room 900 failed](/workshop/03-compensated-lanes.jpg)

## Why it matters

Written by hand, this is the part that goes wrong: remembering which rooms were booked, undoing them in reverse, retrying with backoff, giving up on a worker that never answers, coping with an answer that arrives *after* you gave up. Here it is declared, and it is the same for every process. See [Retries, timeouts and compensation](/guides/retries-timeouts-compensation/) and [Why a process engine?](/guides/why-a-process-engine/).

## Check

- [ ] BOOKING-3 is **Compensated**; *Cancel the room* ran twice.
- [ ] Room 900's own run (double-click its lane header) says why: `SOLD_OUT`. Module 8 comes back to this.
