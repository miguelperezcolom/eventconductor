---
title: "9g · Debugging a failing test"
description: What a failing process assertion prints — every step and variable of the run — and how to read it.
---

Change module 3's policy from `compensateAll` to `continue` and run the tests. The definition is still valid, so validation passes; the behaviour changed, so a test fails — and its message is the run, as the console would show it:

```text
[ERROR] com.example.hotel.FailurePathsTest.compensateAllCancelsEveryRoomThatWasBooked -- <<< FAILURE!
java.lang.AssertionError:
Expected process 7c311a8e-… (rooms-booking-safe, businessKey FAIL-1) to be COMPENSATED but it is COMPLETED.
Steps:
  start                    COMPLETED
  register-booking         COMPLETED
  rooms                    COMPLETED
    #0 "Room 101"          COMPLETED
    #1 "Room 102"          COMPLETED
    #2 "Room 900"          ERROR
  notify-guest             COMPLETED
  done                     COMPLETED
  cancel-room-booking      CANCELLED
Variables: {rooms=[…], guest=Bob, bookingNumber=BK-2,
  roomBookings=[{"status":"COMPLETED","value":"RB-101"},{"status":"COMPLETED","value":"RB-102"},
                {"status":"FAILED","error":"ERROR"}]}
```

Read it top-down, as in [module 8](/workshop/08-debugging/):

1. **The status** — expected *compensated*, got *completed*: the process did not roll back.
2. **The steps** — `rooms` completed, and under it each iteration, named by its index and the forEach's `label` (by its item when there is no label): two rooms completed, `Room 900` ended in `ERROR`. Yet `notify-guest` ran and `cancel-room-booking` was *cancelled*: the failed room did not stop the process.
3. **The variables** — `roomBookings` has a `status` per room: that shape is what `continue` produces. The policy changed.

An assertion on the iterations says it room by room — `assertThat(booking).iterations("rooms").hasCompensated("Room 101", "Room 102")` fails with each room's status, what it reported and whether it was undone:

```text
Expected the compensation of step 'rooms' of process … to have undone [#0 "Room 101", #1 "Room 102"], but it undid none:
  #0 "Room 101"                COMPLETED {roomBookingId=RB-101}
  #1 "Room 102"                COMPLETED {roomBookingId=RB-102}
  #2 "Room 900"                ERROR
```

When the message is not enough:

- **`runtime.invocationsOf(step)`** — every dispatch of a step, with the variables it was sent and the engine time; print it.
- **`booking.stepExecutions(step)`** — the step's executions with their status and reason.
- **Set a breakpoint in a handler** when the test boots the real handlers: everything runs on the test's thread, so the debugger stops there with the whole call stack.
- **Reproduce a production run**: copy its variables with *Copy state* (module 8) into `runtime.start(…)`.
