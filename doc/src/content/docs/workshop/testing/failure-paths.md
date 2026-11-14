---
title: "9b · When things fail"
description: Test the sold-out room — nothing is undone until the guest decides; their cancel compensates once per booked room; compensateAll undoes on its own.
---

The failure paths are where a process earns its keep, and where tests pay off most: nobody clicks through a guest's cancellation by hand before every release. From `FailurePathsTest.java`:

```java
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
```

- **`isWaitingAt("wait-for-guest")`** and **`hasNoCompensations()`** pin the rule of module 3: a sold-out room undoes nothing by itself.
- **`runtime.sendMessage("guest-answer", "FAIL-2", Map.of("answer", "cancel"))`** is the guest's decision, delivered exactly as `scripts/answer.sh` delivers it. After it, **`hasCompensated("rooms")`** says the rollback undid the step and **`hasNotPassedSteps`** that no confirmation went out.
- **`iterations("rooms")`** asserts room by room. An iteration is named by its **label** — the forEach's `label`, `'Room ' + room.number` — by its index (`2`) or by its item; `hasOnlyFailed("Room 900")` means that room failed *and no other did*, `hasNoneCompensated()` that nothing was undone yet, and `hasCompensated("Room 101", "Room 102")` that the rollback undid exactly those two. When one of them fails, the message names the rooms the same way (`#2 "Room 900"`), never by an execution id.
- `invocationsOf("cancel-room-booking")` is still the way to check what each compensation was *sent*.

The last test runs a **test-only** variant of module 3 with `onIterationFailure: compensateAll`, from `src/test/flows/rooms-booking-all-or-none.ec.yaml` — all rooms or none, decided by the engine on its own, which is right only when the rooms are one unit nobody needs to be asked about (module 3, *When undoing on its own is right*). Writing both is how a team *decides* which behaviour a booking should have, and keeps it.
