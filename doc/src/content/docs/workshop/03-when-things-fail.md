---
title: "3 · When things fail"
description: Retries and timeouts for what is technical; for a sold-out room, the guest decides — and compensation undoes what was booked only if the guest cancels.
---

The hotel's penthouse is always sold out. Book two doubles and the penthouse, and the third room fails. What should happen to the two that were booked?

Not "cancel them behind the guest's back". A real hotel tells the guest, and **the guest decides**: keep the two rooms, or cancel the whole booking. Only a cancellation undoes what was booked — and undoing it is a **saga compensation**, which EventConductor runs for you, room by room, once someone has decided to.

Two kinds of failure, two answers:

- **Technical** — the hotel's system is slow, a worker crashes, an answer never comes. Retry it, with backoff, and give up after a timeout. Nobody needs to be asked.
- **Business** — the room is sold out. Retrying will not free it. Keep what was booked and ask the person whose booking it is.

## The definition

`03-rooms-booking-safe.ec.yaml` — the rooms flow of module 2, plus failure handling:

```yaml
id: rooms-booking-safe
name: Book every room (the guest decides)
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
    compensationStepId: cancel-room-booking  # how to undo a room that was booked — if the guest cancels
    outputVariables: [roomBookingId]
    forEach:
      items: rooms
      as: room
      label: "'Room ' + room.number"
      onIterationFailure: continue           # a sold-out room stops nothing: keep what was booked
      output: roomBookings                   # each room: {"status": "COMPLETED", "value": …} or FAILED
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

  - id: all-booked
    type: CHOICE
    name: Every room booked?
    preconditionStepId: rooms

  - id: ask-guest                            # some room could not be booked: tell the guest, ask
    type: ACTION
    name: Ask the guest
    retries: 3
    preconditions:
      - stepId: all-booked
        expression: "roomBookings =~ '.*FAILED.*'"

  - id: wait-for-guest
    type: WAIT_FOR_MESSAGE
    name: Wait for the guest's answer
    preconditionStepId: ask-guest
    messageName: guest-answer                # {"answer": "keep"} or {"answer": "cancel"}
    correlationExpression: businessKey
    timeout: P2D
    onTimeoutStepId: tell-front-desk         # no answer: nothing is cancelled, a person follows up

  - id: guest-decision
    type: CHOICE
    name: What did the guest decide?
    preconditionStepId: wait-for-guest

  - id: cancelled-by-guest                   # the guest's decision: undo every room that was booked
    type: END
    name: Cancelled by the guest
    compensate: true
    preconditions:
      - stepId: guest-decision
        expression: "answer == 'cancel'"

  - id: notify-guest                         # the default of all-booked: every room was booked
    type: ACTION
    name: Tell the guest
    retries: 3
    preconditionStepId: all-booked

  - id: done
    type: END
    name: Done
    preconditionStepIds: [notify-guest, guest-decision]   # whichever comes: all booked, or the guest keeps them

  - id: tell-front-desk
    type: ACTION
    name: Tell the front desk
    preconditions:
      - stepId: wait-for-guest
        expression: "false"                  # reached only through onTimeoutStepId

  - id: held
    type: END
    name: Held for the guest
    preconditionStepId: tell-front-desk

  - id: cancel-room-booking                  # the compensation: no preconditions, run by the rollback
    type: ACTION
    name: Cancel the room
```

| Line | What it does |
|---|---|
| `retries: 2` | A technical failure (an exception, a timeout) is retried twice, with exponential backoff |
| `timeout: PT30S` | A worker that never answers fails the attempt after 30 seconds — and that is retried too |
| `onIterationFailure: continue` | A room that fails for good stops nothing: the others are booked and **kept**, the step completes, and `roomBookings` says how each room went. The alternatives: `failFast` (the default — stop at the first failure and roll back) and `compensateAll` (let every room finish, then undo them all if one failed) |
| `all-booked` (CHOICE) | Every room booked: tell the guest. Some room `FAILED`: ask them |
| `wait-for-guest` | Waits — for days if need be, holding no thread — for the message `guest-answer`, correlated by the booking's business key |
| `timeout: P2D` + `onTimeoutStepId` | No answer in two days is **not** a cancellation: the front desk is told to call the guest, and the booking stays as it is |
| `compensable` + `compensationStepId` | *How* to undo a booked room: `cancel-room-booking`. Declaring it undoes nothing by itself |
| `cancelled-by-guest` — an `END` with `compensate: true` | *When* to undo: the guest said cancel. Reaching it runs `cancel-room-booking` once for every room that was booked, newest first, and the process ends **Compensated** |

**Compensation is the mechanism; who decides to trigger it is your business rule.** Here the rule is the guest's answer. (A failure for good — a step that runs out of retries — still rolls the saga back on its own: that is the other way a compensation is triggered, see *When undoing on its own is right* below.)

The handler says *why* the room failed, with a business code, and that the failure is final — retrying will not make the penthouse free:

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

The process does not fail and does not undo anything. `rooms` shows **2/3** — open it (double-click) to see Room 900 failed with `SOLD_OUT` and 101 and 102 completed — *Ask the guest* has run, and the process waits at *Wait for the guest's answer*. The step's log says how the rooms went: *forEach on step rooms completed: 2 of 3 iterations completed, 1 failed (onIterationFailure continue).*

![Waiting for the guest: rooms 2/3 — Room 101 and 102 booked, Room 900 failed — nothing cancelled, the guest asked](/workshop/03-compensated-lanes.jpg)

Now answer as the guest. The hotel's website would send this message when the guest clicks a link in the email; `scripts/answer.sh` sends it to the engine's message API:

```bash
scripts/answer.sh BOOKING-3 cancel
```

The process takes *Cancelled by the guest*. Its log says *Rolling the process back on purpose: the flow reached END 'cancelled-by-guest', which compensates what the process did.* — and *Cancel the room*, drawn amber because it is a compensation, runs **2/2**: one cancellation per room that had been booked; the penthouse, never booked, is not cancelled. The process ends **Compensated**.

![The guest cancelled: the flow went through What did the guest decide? to Cancelled by the guest, and Cancel the room ran 2/2](/workshop/03-compensated-graph.jpg)

Start another one (BOOKING-3B) and answer `keep` instead: *What did the guest decide?* takes its default branch straight to *Done*, and the process ends *Completed* with the two rooms booked and nothing undone. Leave a third one unanswered and nothing happens to it for two days; then the front desk is told — in a test, those two days pass in milliseconds ([Part 9 · Time](/workshop/testing/time/)).

## When undoing on its own is right

Automatic compensation is the right default when the steps are **one technical unit** that is worth nothing in part — charging a card and reserving the stock within one request, or a tour operator's package that is sold whole. Nobody is asked, because there is nobody whose decision it is: `onIterationFailure: compensateAll` (or a step that fails for good) undoes what was done, newest first, and the process ends *Compensated*. The workshop's test-only flow `src/test/flows/rooms-booking-all-or-none.ec.yaml` is module 3 written that way; [Part 8 · Failure paths](/workshop/testing/failure-paths/) tests both.

A saga declared with `compensable` keeps that second trigger: if a later step fails *for good* — it ran out of its retries — the engine rolls the whole process back on its own, cancelling the booked rooms too. That is why the steps after `rooms` here have `retries`: a confirmation email that fails once is retried, not a reason to cancel a booking.

## Why it matters

Written by hand, this is the part that goes wrong: remembering which rooms were booked, undoing exactly those in reverse, waiting days for an answer without holding a thread, retrying with backoff, giving up on a worker that never answers, coping with an answer that arrives *after* you gave up. Here it is declared, and it is the same for every process. See [Retries, timeouts and compensation](/guides/retries-timeouts-compensation/#compensation-on-a-business-decision) and [Why a process engine?](/guides/why-a-process-engine/).

## Check

- [ ] BOOKING-3 waits at *Wait for the guest's answer*, with rooms 101 and 102 booked and nothing cancelled.
- [ ] Room 900's own run (double-click its lane header) says why: `SOLD_OUT`. Module 8 comes back to this.
- [ ] After `scripts/answer.sh BOOKING-3 cancel` it is **Compensated**; *Cancel the room* ran twice.
- [ ] With `keep`, the rooms are confirmed and nothing is cancelled.
