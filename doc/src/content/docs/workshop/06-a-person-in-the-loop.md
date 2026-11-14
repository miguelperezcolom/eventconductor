---
title: "6 · A person in the loop"
description: A manager approves large bookings, and the guest decides what happens to a room the hotel cannot book — USER_TASKs with assignment, escalation and timeouts, CHOICEs on the answers, a reminder and a front desk, and an END that compensates only when somebody decided to cancel.
---

Large bookings need a manager's approval **before** any room is booked. And when the hotel cannot book a room, a second person decides: the guest. People may answer in a minute or in two days; the process waits without holding a thread, and the definition says what happens if nobody answers — which, for the guest, is never "cancel".

## The definition

`06-hotel-booking.ec.yaml` — the whole booking: approval, the rooms at the hotel, and, for a room the hotel cannot book, its alternatives and the guest's decision:

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

  - id: rejected
    type: END
    name: Rejected
    preconditionStepId: decision            # unguarded: the CHOICE's default branch

  - id: rooms
    type: HTTP_CALL
    name: Book every room at the hotel
    preconditions:
      - stepId: decision
        expression: "approved == 'true'"
    compensable: true
    compensationStepId: cancel-room          # how to undo a room — when the guest decides to cancel
    retries: 3
    timeout: PT15S
    forEach:
      items: rooms
      as: room
      label: "'Room ' + room.number"
      onIterationFailure: continue           # a refused room stops nothing: keep what was booked
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

  - id: all-booked
    type: CHOICE
    name: Every room booked?
    preconditionStepId: rooms

  - id: notify-guest                         # the default: every room was booked
    type: ACTION
    name: Tell the guest
    retries: 3
    preconditionStepId: all-booked

  # --- A room could not be booked: the hotel proposes, the guest decides -------------------------

  - id: find-alternatives
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
        alternatives: "body"

  - id: ask-guest
    type: ACTION
    name: Propose the alternatives to the guest
    retries: 3
    preconditionStepId: find-alternatives

  - id: wait-for-guest
    type: WAIT_FOR_MESSAGE
    name: Wait for the guest's answer
    preconditionStepId: ask-guest
    messageName: guest-answer                # {"answer": "accept" | "keep" | "cancel"}
    correlationExpression: businessKey
    timeout: P1D
    onTimeoutStepId: remind-guest            # no answer in a day: remind — never cancel

  - id: remind-guest
    type: ACTION
    name: Remind the guest
    retries: 3
    preconditions:
      - stepId: wait-for-guest
        expression: "false"                  # reached only through onTimeoutStepId

  - id: wait-after-reminder
    type: WAIT_FOR_MESSAGE
    name: Wait for the guest's answer (reminded)
    preconditionStepId: remind-guest
    messageName: guest-answer
    correlationExpression: businessKey
    timeout: P1D
    onTimeoutStepId: front-desk              # still nothing: a person calls the guest

  - id: front-desk
    type: USER_TASK
    name: Front desk calls the guest
    formId: guest-follow-up                  # records the guest's answer: accept, keep or cancel
    assignment:
      candidateGroups: [front-desk, front-desk-managers]
    preconditions:
      - stepId: wait-after-reminder
        expression: "false"                  # reached only through onTimeoutStepId

  - id: answered
    type: JOIN
    name: Answered
    joinType: XOR                            # whichever answer came: the message, or the front desk
    preconditionStepIds: [wait-for-guest, wait-after-reminder, front-desk]

  - id: guest-decision
    type: CHOICE
    name: What did the guest decide?
    preconditionStepId: answered

  - id: book-alternatives                    # accept: book what the hotel offered
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

  - id: confirm-alternatives
    type: ACTION
    name: Confirm the booking to the guest
    retries: 3
    preconditionStepId: book-alternatives

  - id: confirm-kept                         # the default: keep the rooms that were booked
    type: ACTION
    name: Confirm the rooms kept
    retries: 3
    preconditionStepId: guest-decision

  - id: cancelled-by-guest                   # cancel: the guest's decision undoes what was booked
    type: END
    name: Cancelled by the guest
    compensate: true
    preconditions:
      - stepId: guest-decision
        expression: "answer == 'cancel'"

  - id: done
    type: END
    name: Booked
    preconditionStepIds: [notify-guest, confirm-alternatives, confirm-kept]   # whichever comes

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
- **The guest decides** (modules 3 and 4, grown up): a room the hotel refuses is set aside (`continue`), the hotel's alternatives are proposed, and `wait-for-guest` waits for the message `guest-answer`. No answer in a day: `remind-guest`, and a second wait; still nothing: the **front desk** — a second `USER_TASK`, form `guest-follow-up` — calls the guest and records `accept`, `keep` or `cancel`. The XOR `JOIN` `answered` goes on with whichever answer came. Nothing on the way cancels anything.
- **`cancelled-by-guest`** is the only place the booked rooms are undone: an `END` with `compensate: true`, reached when the guest (or the front desk, for the guest) answered `cancel`.

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

The console shows that inbox under **Forms → Tasks** (the workshop's console mounts the forms engine's `FormsOperationsMenu` beside the engine's `WorkflowMenu`, in `ui/Home.java`). The approval is offered to `front-desk-managers` only, so the inbox looks empty until you are one of them.

The workshop has no login server, so act as the manager with its **laptop-only user switch**: open

```text
http://localhost:8095/dev/act-as/manager
```

The browser is now *mia*, of `front-desk-managers`, and lands on **Forms → Tasks**. Pick BOOKING-7's *booking-approval* task, **Run** it, **Claim** it, tick *Approve this booking*, write a comment (`Regular guest`) and **Complete**. `http://localhost:8095/dev/act-as/nobody` makes the browser anonymous again.

:::caution[Local use only]
The switch (`dev/ActAs.java`) leaves an **unsigned** token where the console keeps its sign-in token (the `__mateu_auth_token` cookie), and the app believes it only because the workshop sets `workflow.security.trust-forwarded-token=true` — a shortcut for what a gateway does after verifying a real login. Anybody can type such a token. The switch answers only while that setting is on and only to a browser on the same machine, but neither it nor the setting belongs in anything reachable from a network: there, sign in for real (Spring Security, or a gateway in front that verifies the token).
:::

Or, from a terminal, the script does the same through the forms engine's task API (`/forms/api/tasks`), as *mia*:

```bash
scripts/approve.sh BOOKING-7 true 'Regular guest'
```

(It finds the booking's open task, claims it and completes it with `{"approved": true, "comment": "Regular guest"}`, sending the same unsigned token as a bearer header.)

An operator can also decide it from the console, without the form: **Complete step**, step `approve`, output variable `approved = true`, and a reason — which goes to the process's audit trail with your name. That is the way out when the manager approved by phone (module 7).

![Completing the approval from the console](/workshop/06-approve-from-console.gif)

The CHOICE takes the approved branch, the rooms are booked at the hotel, the guest is told:

![Booked: approval, choice, rooms 3/3, guest told](/workshop/06-booked.jpg)

Start another one (BOOKING-8) and reject it — leave *Approve this booking* unticked in the form, or `scripts/approve.sh BOOKING-8 false`: the CHOICE takes the default branch, and the run finishes through the *Rejected* end (status *Completed* — rejecting is a normal outcome, not a failure) without touching the hotel.

## When a room cannot be booked

Start a booking with the penthouse, and approve it:

```bash
scripts/start.sh hotel-booking BOOKING-9 '{"guest": "Lee", "rooms": [
  {"number": "401", "type": "double"}, {"number": "900", "type": "penthouse"}]}'
scripts/approve.sh BOOKING-9 true 'VIP'
```

Room 401 is booked, the penthouse is refused, the hotel offers suite 901 and the guest is asked: the process waits at *Wait for the guest's answer*. Now the guest decides — `scripts/answer.sh BOOKING-9 accept` books the suite and confirms; `keep` confirms room 401 alone; `cancel` ends at *Cancelled by the guest*, where room 401 is cancelled at the hotel and the process ends **Compensated**.

If nobody answers, nothing is cancelled. After a day the guest is reminded; after two, the task *Front desk calls the guest* appears under **Forms → Tasks** (for `front-desk`, and for *mia* of `front-desk-managers` in the workshop): the front desk calls and records what the guest wants. Choosing *Cancel the whole booking* there is the same decision, taken for the guest — and the same compensation.

## Check

- [ ] BOOKING-7 waits at *Manager approval* until you complete it.
- [ ] As the manager (`/dev/act-as/manager`), its task is under **Forms → Tasks**, and completing the form there moves the process on.
- [ ] With `approved = true` it ends at **Booked**; with `false`, at **Rejected** — and the hotel holds nothing for it.
- [ ] BOOKING-9 waits for the guest with room 401 still booked; `cancel` makes it **Compensated** and the hotel holds nothing for it.
- [ ] In a test, the reminder after a day and the front desk after two pass in milliseconds — [Part 9 · Time](/workshop/testing/time/).
- [ ] In a test, the three days of the timeout pass in milliseconds — [Part 9 · Time](/workshop/testing/time/).
