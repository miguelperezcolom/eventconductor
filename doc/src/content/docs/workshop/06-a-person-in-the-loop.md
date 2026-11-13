---
title: "6 · A person in the loop"
description: A manager approves large bookings — a USER_TASK with assignment, escalation and a timeout, a CHOICE on the answer, and completing the task from the console.
---

Large bookings need a manager's approval **before** any room is booked. A person may answer in a minute or in two days; the process waits without holding a thread, and the definition says what happens if nobody answers.

## The definition

`06-hotel-booking.yaml` — the whole booking: approval, then the rooms at the hotel (all or none), then the guest:

```yaml
id: hotel-booking
name: Hotel booking
steps:
  - id: start
    type: START
    name: Booking received

  - id: approve
    type: USER_TASK
    name: Manager approval
    preconditionStepId: start
    formId: booking-approval
    assignment:
      candidateGroups: [front-desk-managers]
    escalation:
      - after: P1D                           # still open after a day: say so
        action: notify
    timeout: P3D                             # nobody decided in three days: the step times out

  - id: decision
    type: CHOICE
    name: Approved?
    preconditionStepId: approve

  - id: rooms
    type: HTTP_CALL
    name: Book every room at the hotel
    preconditions:
      - stepId: decision
        expression: "approved == 'true'"
    compensable: true
    compensationStepId: cancel-room
    retries: 3
    timeout: PT15S
    forEach:
      items: rooms
      as: room
      label: "'Room ' + room.number"
      onIterationFailure: compensateAll
      output: roomBookings
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

  - id: notify-guest
    type: ACTION
    name: Tell the guest
    preconditionStepId: rooms

  - id: done
    type: END
    name: Booked
    preconditionStepId: notify-guest

  - id: rejected
    type: END
    name: Rejected
    preconditionStepId: decision            # unguarded: the CHOICE's default branch

  - id: cancel-room
    type: HTTP_CALL
    name: Cancel the room at the hotel
    retries: 5
    http:
      connection: hotel
      method: DELETE
      path: "/bookings/by-ref/${businessKey}-${room.number}"
      successStatus: [200, 204, 404]
```

- **`USER_TASK`** waits for a person to submit a form (`formId`). `assignment` says who may take it, `escalation` what happens while it stays open (notify, reassign, or route the process elsewhere), and `timeout` the hard limit. See [Human tasks: assignment and escalation](/guides/human-tasks-assignment-and-escalation/).
- **`CHOICE`** takes exactly one branch: the first whose guard holds (`approved == 'true'`), or the unguarded default (`rejected`).

## Run it

```bash
scripts/start.sh hotel-booking BOOKING-7 '{"guest": "Fay", "rooms": [
  {"number": "101", "type": "double"}, {"number": "102", "type": "double"},
  {"number": "103", "type": "double"}]}'
```

The process stops at the approval, drawn yellow; everything after it waits, faded:

![Waiting for the manager's approval](/workshop/06-waiting-for-approval.jpg)

## Approve it

In a full deployment the **forms engine** serves the `booking-approval` form (the project has it in `forms/booking-approval.ecform`) and the manager finds it in the **task inbox**. In this single-app workshop the forms engine is not running (see the note below), so you approve as an **operator**: **Complete step**, step `approve`, output variable `approved = true`, and a reason — which goes to the process's audit trail with your name.

![Completing the approval from the console](/workshop/06-approve-from-console.gif)

The CHOICE takes the approved branch, the rooms are booked at the hotel, the guest is told:

![Booked: approval, choice, rooms 3/3, guest told](/workshop/06-booked.jpg)

Try one with `approved = false`: the CHOICE takes the default branch, and the run finishes through the *Rejected* end (status *Completed* — rejecting is a normal outcome, not a failure) without touching the hotel.

:::note[Why not the form, here]
With the engine **embedded** in one application, adding the `forms-engine` dependency currently stops the app from starting (*"HumanTaskEvents required a bean of type StreamBridge"*): the forms engine publishes task events through Spring Cloud Stream, which embedded mode switches off. Until that is fixed, run the forms engine as its own service (the [standalone apps](/guides/deployment-modes/)) to get the form and the task inbox; the definition does not change.
:::

## Check

- [ ] BOOKING-7 waits at *Manager approval* until you complete it.
- [ ] With `approved = true` it ends at **Booked**; with `false`, at **Rejected** — and the hotel holds nothing for it.
- [ ] In a test, the three days of the timeout pass in milliseconds — [Part 9 · Time](/workshop/testing/time/).
