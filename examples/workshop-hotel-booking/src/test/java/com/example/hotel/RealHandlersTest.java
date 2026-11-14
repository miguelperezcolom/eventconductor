package com.example.hotel;

import com.example.hotel.workers.HotelTaskHandlers;
import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestApplication;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;

/** Testing, part f — the real task handlers answer every task no mock does. */
@EventConductorTest(classes = {WorkflowTestApplication.class, HotelTaskHandlers.class})
class RealHandlersTest {

    @Test
    void theRealHandlersBookEveryRoom(WorkflowTestRuntime runtime) {
        var booking = runtime.start("rooms-booking", "REAL-1",
                Map.of("guest", "Fay", "rooms", Rooms.THREE_DOUBLES));

        assertThat(booking).isCompleted().hasVariable("bookingNumber");
    }

    @Test
    void theRealHandlerRefusesThePenthouseAndTheRestIsUndone(WorkflowTestRuntime runtime) {
        var booking = runtime.start("rooms-booking-safe", "REAL-2",
                Map.of("guest", "Fay", "rooms", Rooms.WITH_A_PENTHOUSE));

        assertThat(booking).isCompensated().hasCompensated("rooms");
        assertThat(booking).iterations("rooms").hasOnlyFailed("Room 900").hasCompensated("Room 101", "Room 102");
    }

    @Test
    void aMockStillWinsOverAHandler(WorkflowTestRuntime runtime) {
        runtime.whenStep("notify-guest").thenFail("MAILBOX_FULL", false);

        var booking = runtime.start("rooms-booking", "REAL-3",
                Map.of("guest", "Fay", "rooms", Rooms.THREE_DOUBLES));

        assertThat(booking).isFailed().hasPassedSteps("rooms");
    }
}
