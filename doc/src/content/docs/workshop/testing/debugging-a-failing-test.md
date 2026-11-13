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
  rooms                    COMPLETED
  rooms                    ERROR
  rooms                    COMPLETED
  notify-guest             COMPLETED
  done                     COMPLETED
  cancel-room-booking      CANCELLED
Variables: {rooms=[…], guest=Bob, bookingNumber=BK-2,
  roomBookings=[{"status":"COMPLETED","value":"RB-101"},{"status":"COMPLETED","value":"RB-102"},
                {"status":"FAILED","error":"ERROR"}]}
```

Read it top-down, as in [module 8](/workshop/08-debugging/):

1. **The status** — expected *compensated*, got *completed*: the process did not roll back.
2. **The steps** — `rooms` appears once for the step and once per iteration: two completed, one `ERROR`. Yet `notify-guest` ran and `cancel-room-booking` was *cancelled*: the failed room did not stop the process.
3. **The variables** — `roomBookings` has a `status` per room: that shape is what `continue` produces. The policy changed.

When the message is not enough:

- **`runtime.invocationsOf(step)`** — every dispatch of a step, with the variables it was sent and the engine time; print it.
- **`booking.stepExecutions(step)`** — the step's executions with their status and reason.
- **Set a breakpoint in a handler** when the test boots the real handlers: everything runs on the test's thread, so the debugger stops there with the whole call stack.
- **Reproduce a production run**: copy its variables with *Copy state* (module 8) into `runtime.start(…)`.
