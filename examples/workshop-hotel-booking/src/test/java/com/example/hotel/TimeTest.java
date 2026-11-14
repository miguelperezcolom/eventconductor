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
