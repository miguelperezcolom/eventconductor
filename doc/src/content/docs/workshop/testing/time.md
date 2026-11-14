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
    void aHotelThatNeverAnswersIsRetriedThenTheGuestIsAsked(WorkflowTestRuntime runtime) {
        runtime.whenStep("register-booking").thenComplete();
        runtime.whenStep("create-room-booking").thenTimeout();            // the worker never answers
        runtime.whenStep("ask-guest").thenComplete();

        var booking = runtime.start("rooms-booking-safe", "TIME-1",
                Map.of("guest", "Carol", "rooms", "[{\"number\": \"101\", \"type\": \"double\"}]"));
        assertThat(booking).isRunning();

        runtime.advanceTime(Duration.ofMinutes(5));                       // 30 s timeout, 2 retries

        assertThat(runtime.invocationsOf("create-room-booking")).hasSize(3);   // 1 + 2 retries
        assertThat(booking).isWaitingAt("wait-for-guest")                 // given up on: the guest is asked
                .hasNotPassedSteps("notify-guest");
    }

    @Test
    void aGuestWhoNeverAnswersIsRemindedThenTheFrontDeskCalls(WorkflowTestRuntime runtime) {
        var hotel = runtime.whenHttpCall("rooms").thenRespond(201, "{\"id\": \"H-1\"}");
        hotel.whenItemHas("number", "103").thenRespond(409, "sold out");
        runtime.whenHttpCall("find-alternatives").thenRespond(200, "[{\"number\": \"104\", \"type\": \"suite\"}]");
        runtime.whenStep("ask-guest").thenComplete();
        runtime.whenStep("remind-guest").thenComplete();

        var booking = runtime.start("hotel-booking", "TIME-3",
                Map.of("guest", "Gus", "rooms", Rooms.THREE_DOUBLES));
        runtime.completeUserTask(booking, "approve", Map.of("approved", true));
        assertThat(booking).isWaitingAt("wait-for-guest");

        runtime.advanceTime(Duration.ofDays(1).plusMinutes(1));
        assertThat(booking).isWaitingAt("wait-after-reminder").hasPassedSteps("remind-guest");

        runtime.advanceTime(Duration.ofDays(1));
        assertThat(booking).isWaitingAt("front-desk")                     // a person decides with the guest…
                .hasNoCompensations();                                    // …and nothing was cancelled meanwhile
    }

    @Test
    void anApprovalNobodyGivesTimesOutAfterThreeDays(WorkflowTestRuntime runtime) {
        var booking = runtime.start("hotel-booking", "TIME-2",
                Map.of("guest", "Dan", "rooms", Rooms.THREE_DOUBLES));
        assertThat(booking).isWaitingAt("approve");

        runtime.advanceTime(Duration.ofDays(2));
        assertThat(booking).isWaitingAt("approve")                        // still open…
                .hasEscalated("approve", 1);                              // …and the one-day escalation fired

        runtime.advanceTime(Duration.ofDays(1).plusMinutes(1));
        assertThat(booking).hasStepStatus("approve", StepExecutionStatus.TIMEOUT)
                .hasNotPassedSteps("rooms");
    }
}
```

**`advanceTime(duration)`** moves the engine's clock forward, stopping at every moment something is due on the way — each step timeout, each retry's backoff, each escalation, each timer — and letting the engine act there. Five minutes of retries and three days of waiting take **milliseconds**.

Details the tests show:

- `thenTimeout()` means *the worker never answers*: the step's own `timeout` (30 s) fails each attempt, and each is retried with the real backoff policy — three dispatches in all.
- A hotel that never answers is a *technical* failure, and it is given up on after its retries — but the room is then set aside like a sold-out one (`continue`), and the guest is asked. Nothing was booked, nothing is undone.
- The second test is module 6's deadline: a day without an answer reminds the guest, a second day puts the decision in the front desk's hands — and `hasNoCompensations()` pins that waiting never cancelled anything.

`hasEscalated("approve", 1)` checks that exactly one level of the task's `escalation` fired (`hasEscalated("approve")` for at least one, `hasNotEscalated` for none yet); when it fails, the message lists each level with its `after`, its action and whether it fired.

A timeout fires strictly after its limit, so a step with a three-day timeout is still waiting at exactly three days and has timed out a moment later — hence the `plusMinutes(1)`.

For assertions on retry timing (`invocation.at()`), set `workflow.retry.backoff-jitter=0.0` in `@EventConductorTest(properties = …)` to make the moments exact. See [Moving time](/guides/testing-workflows/#moving-time).
