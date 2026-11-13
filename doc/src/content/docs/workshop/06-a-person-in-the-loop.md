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

The **forms engine** runs inside the app (the `forms-engine` dependency): it reads the `booking-approval` form from `forms/booking-approval.ecform` at startup (`forms.directory-import.directories` in `application.properties`), takes the USER_TASK from the engine — no broker in between — and puts it in the **task inbox** of the `front-desk-managers` group. A manager claims the task and submits the form; the form's values (`approved`, `comment`) become process variables.

The workshop has no login server, so act as the manager with the script, which calls the forms engine's task API (`/forms/api/tasks`) as *mia*, of `front-desk-managers`:

```bash
scripts/approve.sh BOOKING-7 true 'Regular guest'
```

(It finds the booking's open task, claims it and completes it with `{"approved": true, "comment": "Regular guest"}`. The app takes the caller from the token as it comes — `workflow.security.trust-forwarded-token=true`, a laptop-only shortcut for what a gateway does after verifying a real login.)

An operator can also decide it from the console, without the form: **Complete step**, step `approve`, output variable `approved = true`, and a reason — which goes to the process's audit trail with your name. That is the way out when the manager approved by phone (module 7).

![Completing the approval from the console](/workshop/06-approve-from-console.gif)

The CHOICE takes the approved branch, the rooms are booked at the hotel, the guest is told:

![Booked: approval, choice, rooms 3/3, guest told](/workshop/06-booked.jpg)

Start another one (BOOKING-8) and reject it — `scripts/approve.sh BOOKING-8 false`: the CHOICE takes the default branch, and the run finishes through the *Rejected* end (status *Completed* — rejecting is a normal outcome, not a failure) without touching the hotel.

## Check

- [ ] BOOKING-7 waits at *Manager approval* until you complete it.
- [ ] With `approved = true` it ends at **Booked**; with `false`, at **Rejected** — and the hotel holds nothing for it.
- [ ] In a test, the three days of the timeout pass in milliseconds — [Part 9 · Time](/workshop/testing/time/).
