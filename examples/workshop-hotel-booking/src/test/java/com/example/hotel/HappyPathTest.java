package com.example.hotel;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.TaskOutcome;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Testing, part a — the happy path: every task answered by a mock, the result checked. */
@EventConductorTest
class HappyPathTest {

    @Test
    void booksEveryRoomAndTellsTheGuest(WorkflowTestRuntime runtime) {
        runtime.whenStep("register-booking").thenComplete(Map.of("bookingNumber", "BK-1"));
        // One answer per room, computed from what the task was sent:
        runtime.whenStep("create-room-booking").thenAnswer(task ->
                TaskOutcome.complete(Map.of("roomBookingId", "RB-" + task.variable("room").replaceAll("\\D", ""))));
        runtime.whenStep("notify-guest").thenComplete();

        var booking = runtime.start("rooms-booking", "HAPPY-1",
                Map.of("guest", "Alice", "rooms", Rooms.THREE_DOUBLES));

        assertThat(booking).isCompleted()
                .hasPassedSteps("register-booking", "rooms", "notify-guest")
                .hasVariable("bookingNumber", "BK-1")
                .hasVariable("roomBookings", "[\"RB-101\", \"RB-102\", \"RB-103\"]");
        assertThat(runtime.invocationsOf("create-room-booking")).hasSize(3);
    }
}
