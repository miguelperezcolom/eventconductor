package com.example.hotel;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Testing, part c — time: days pass in milliseconds. */
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
