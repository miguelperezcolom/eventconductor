---
title: "9c · Time"
description: Test timeouts, retries and a three-day approval deadline in milliseconds, by moving the engine's clock.
---

A worker that never answers, a backoff between retries, a manager who does not decide for three days: the paths that depend on **time** are the ones nobody tests by hand. In the kit, time only moves when the test moves it — and it moves instantly.

From `TimeTest.java`:

```java
@EventConductorTest
class TimeTest {

    @Test
    void aHotelThatNeverAnswersIsRetriedThenGivenUpOn(WorkflowTestRuntime runtime) {
        runtime.whenStep("register-booking").thenComplete();
        runtime.whenStep("create-room-booking").thenTimeout();            // the worker never answers
        runtime.whenStep("cancel-room-booking").thenComplete();

        var booking = runtime.start("rooms-booking-safe", "TIME-1",
                Map.of("guest", "Carol", "rooms", "[{\"number\": \"101\", \"type\": \"double\"}]"));
        assertThat(booking).isRunning();

        runtime.advanceTime(Duration.ofMinutes(5));                       // 30 s timeout, 2 retries

        assertThat(runtime.invocationsOf("create-room-booking")).hasSize(3);   // 1 + 2 retries
        assertThat(booking).isFailed().hasNotPassedSteps("notify-guest");   // nothing was booked: nothing to undo
    }

    @Test
    void anApprovalNobodyGivesTimesOutAfterThreeDays(WorkflowTestRuntime runtime) {
        var booking = runtime.start("hotel-booking", "TIME-2",
                Map.of("guest", "Dan", "rooms", Rooms.THREE_DOUBLES));
        assertThat(booking).isWaitingAt("approve");

        runtime.advanceTime(Duration.ofDays(2));
        assertThat(booking).isWaitingAt("approve");                       // escalated, still open

        runtime.advanceTime(Duration.ofDays(1).plusMinutes(1));
        assertThat(booking).hasStepStatus("approve", StepExecutionStatus.TIMEOUT)
                .hasNotPassedSteps("rooms");
    }
}
```

**`advanceTime(duration)`** moves the engine's clock forward, stopping at every moment something is due on the way — each step timeout, each retry's backoff, each escalation, each timer — and letting the engine act there. Five minutes of retries and three days of waiting take **milliseconds**.

Two details the first test shows:

- `thenTimeout()` means *the worker never answers*: the step's own `timeout` (30 s) fails each attempt, and each is retried with the real backoff policy — three dispatches in all.
- With nothing booked, there is nothing to compensate: the run ends **failed** (`ERROR`), not *compensated*. That is a behaviour worth pinning in a test.

A timeout fires strictly after its limit, so a step with a three-day timeout is still waiting at exactly three days and has timed out a moment later — hence the `plusMinutes(1)`.

For assertions on retry timing (`invocation.at()`), set `workflow.retry.backoff-jitter=0.0` in `@EventConductorTest(properties = …)` to make the moments exact. See [Moving time](/guides/testing-workflows/#moving-time).
