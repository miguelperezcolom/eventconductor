package com.example.booking;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

@EventConductorTest
class HotelBookingTest {

    static final Map<String, Object> THREE_ROOMS =
            Map.of("rooms", "[{\"number\":101},{\"number\":102},{\"number\":103}]");

    @Test
    void booksEveryRoom(WorkflowTestRuntime runtime) {
        runtime.whenTask("create-booking").thenComplete(Map.of("roomBookingId", "HB-1"));
        runtime.whenTask("confirm-booking").thenComplete();
        runtime.whenTask("notifications").thenComplete();

        var booking = runtime.start("hotel-booking", "BK-1", THREE_ROOMS);

        assertThat(booking).isCompleted().hasPassedSteps("rooms", "notify");
    }

    @Test
    void undoesTheBookedRoomsWhenOneIsRefused(WorkflowTestRuntime runtime) {
        runtime.whenTask("create-booking").thenComplete(Map.of("roomBookingId", "HB-1"));
        runtime.whenTask("confirm-booking").thenAnswer(task ->
                String.valueOf(task.variable("room")).contains("102")
                        ? io.mateu.workflow.test.TaskOutcome.fail("OVERBOOKED", false)
                        : io.mateu.workflow.test.TaskOutcome.complete(Map.of()));
        runtime.whenTask("cancel-booking").thenComplete();
        runtime.whenTask("notifications").thenComplete();

        var booking = runtime.start("hotel-booking", "BK-2", THREE_ROOMS);

        assertThat(booking).isCompensated().hasNotPassedSteps("notify");
        assertThat(runtime.invocationsOf("cancelRoom")).hasSize(2);   // rooms 101 and 103
    }
}
