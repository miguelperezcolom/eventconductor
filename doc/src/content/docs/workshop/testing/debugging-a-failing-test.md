---
title: "9g · Debugging a failing test"
description: What a failing process assertion prints — every step and variable of the run — and how to read it.
---

Change module 3's policy from `continue` to `compensateAll` and run the tests. The definition is still valid, so validation passes; the behaviour changed, so a test fails — and its message is the run, as the console would show it:

```text
[ERROR] com.example.hotel.FailurePathsTest.aSoldOutRoomKeepsTheOthersAndAsksTheGuest -- <<< FAILURE!
java.lang.AssertionError:
Expected process f72b82ee-… (rooms-booking-safe, businessKey FAIL-1) to be waiting at 'wait-for-guest'.
Steps:
  start                    COMPLETED
  register-booking         COMPLETED
  rooms                    ERROR
    #0 "Room 101"          COMPLETED
    #1 "Room 102"          COMPLETED
    #2 "Room 900"          ERROR
  all-booked               CANCELLED
  ask-guest                CANCELLED
  wait-for-guest           CANCELLED
  guest-decision           CANCELLED
  cancelled-by-guest       CANCELLED
  notify-guest             CANCELLED
  done                     CANCELLED
  tell-front-desk          CANCELLED
  held                     CANCELLED
  cancel-room-booking      COMPLETED
    #0 "Room 102"          COMPLETED
    #1 "Room 101"          COMPLETED
Variables: {rooms=[…], guest=Bob, bookingNumber=BK-2}
```

Read it top-down, as in [module 8](/workshop/08-debugging/):

1. **The status** — expected to be *waiting* for the guest; it is not waiting at all.
2. **The steps** — under `rooms`, each iteration, named by its index and the forEach's `label` (by its item when there is no label): two rooms completed, `Room 900` ended in `ERROR` — and this time `rooms` itself is `ERROR`. Nothing after it ran: the guest was never asked, yet `cancel-room-booking` ran once per booked room, newest first. The engine undid the booking on its own.
3. **The variables** — no `roomBookings` at all: the step failed, so it produced no output. The policy changed.

An assertion on the iterations says it room by room — `assertThat(booking).iterations("rooms").hasNoneCompensated()` fails with each room's status, what it reported and whether it was undone.

When the message is not enough:

- **`runtime.invocationsOf(step)`** — every dispatch of a step, with the variables it was sent and the engine time; print it.
- **`booking.stepExecutions(step)`** — the step's executions with their status and reason.
- **Set a breakpoint in a handler** when the test boots the real handlers: everything runs on the test's thread, so the debugger stops there with the whole call stack.
- **Reproduce a production run**: copy its variables with *Copy state* (module 8) into `runtime.start(…)`.
