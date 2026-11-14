package com.example.hotel;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.TaskOutcome;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Testing, part b — when a room cannot be booked: the guest decides, and only "cancel" undoes. */
@EventConductorTest(definitions = "src/test/flows")
class FailurePathsTest {

    /** Room 900 is sold out; the others are booked. */
    private static void hotelWithASoldOutPenthouse(WorkflowTestRuntime runtime) {
        runtime.whenStep("register-booking").thenComplete(Map.of("bookingNumber", "BK-2"));
        runtime.whenStep("create-room-booking").thenAnswer(task -> task.variable("room").contains("penthouse")
                ? TaskOutcome.fail("SOLD_OUT", false)                       // final: not retried
                : TaskOutcome.complete(Map.of("roomBookingId", "RB-" + task.variable("room").replaceAll("\\D", ""))));
        runtime.whenStep("ask-guest").thenComplete();
        runtime.whenStep("cancel-room-booking").thenComplete();
        runtime.whenStep("confirm-kept").thenComplete();
    }

    @Test
    void aSoldOutRoomKeepsTheOthersAndAsksTheGuest(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);

        var booking = runtime.start("rooms-booking-safe", "FAIL-1",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        assertThat(booking).isWaitingAt("wait-for-guest")                // nothing undone behind the guest's back
                .hasPassedSteps("ask-guest")
                .hasNoCompensations();
        assertThat(booking).iterations("rooms")                           // room by room, by label
                .hasOnlyFailed("Room 900")
                .hasNoneCompensated()
                .iteration("Room 101").hasOutput("roomBookingId", "RB-101");
        assertThat(booking.variable("roomBookings"))                      // the output says how each room went
                .contains("{\"status\":\"COMPLETED\",\"value\":\"RB-101\"}", "{\"status\":\"FAILED\",\"error\":\"ERROR\"}");
    }

    @Test
    void theGuestCancellingUndoesEveryRoomThatWasBooked(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);
        var booking = runtime.start("rooms-booking-safe", "FAIL-2",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        runtime.sendMessage("guest-answer", "FAIL-2", Map.of("answer", "cancel"));   // the guest's decision

        assertThat(booking).isCompensated()
                .hasPassedSteps("cancelled-by-guest")
                .hasCompensated("rooms")                                  // the rollback undid the rooms
                .hasNotPassedSteps("confirm-kept", "notify-guest");
        assertThat(booking).iterations("rooms")
                .hasOnlyFailed("Room 900")
                .hasCompensated("Room 101", "Room 102");                  // the sold-out one had nothing to undo
        // Each cancellation saw the booking id of the room it undid:
        assertThat(runtime.invocationsOf("cancel-room-booking"))
                .extracting(task -> task.variable("roomBookingId"))
                .containsExactlyInAnyOrder("RB-101", "RB-102");
    }

    @Test
    void theGuestKeepingTheRoomsUndoesNothing(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);
        var booking = runtime.start("rooms-booking-safe", "FAIL-3",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        runtime.sendMessage("guest-answer", "FAIL-3", Map.of("answer", "keep"));

        assertThat(booking).isCompleted().hasPassedSteps("confirm-kept").hasNoCompensations();
    }

    @Test
    void compensateAllUndoesEveryRoomOnItsOwn(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);

        var booking = runtime.start("rooms-booking-all-or-none", "FAIL-4",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        assertThat(booking).isCompensated()                               // nobody was asked: all or none
                .hasCompensated("rooms");
        assertThat(booking).iterations("rooms")
                .hasOnlyFailed("Room 900")
                .hasCompensated("Room 101", "Room 102");
    }
}
