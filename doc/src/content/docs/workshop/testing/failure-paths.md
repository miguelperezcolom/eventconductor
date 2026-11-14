---
title: "9b · When things fail"
description: Test the sold-out room — the compensation runs once per booked room with compensateAll, and nothing is undone with continue.
---

The failure paths are where a process earns its keep, and where tests pay off most: nobody clicks through a rollback by hand before every release. From `FailurePathsTest.java`:

```java
@EventConductorTest(definitions = "src/test/flows")
class FailurePathsTest {

    /** Room 900 is sold out: the others are booked, then cancelled — all rooms or none. */
    private static void hotelWithASoldOutPenthouse(WorkflowTestRuntime runtime) {
        runtime.whenStep("register-booking").thenComplete(Map.of("bookingNumber", "BK-2"));
        runtime.whenStep("create-room-booking").thenAnswer(task -> task.variable("room").contains("penthouse")
                ? TaskOutcome.fail("SOLD_OUT", false)                       // final: not retried
                : TaskOutcome.complete(Map.of("roomBookingId", "RB-" + task.variable("room").replaceAll("\\D", ""))));
        runtime.whenStep("cancel-room-booking").thenComplete();
        runtime.whenStep("notify-guest").thenComplete();
    }

    @Test
    void compensateAllCancelsEveryRoomThatWasBooked(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);

        var booking = runtime.start("rooms-booking-safe", "FAIL-1",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        assertThat(booking).isCompensated()
                .hasCompensated("rooms")                                  // the rollback undid the rooms
                .hasNotPassedSteps("notify-guest");
        assertThat(booking).iterations("rooms")                           // room by room, by label
                .hasOnlyFailed("Room 900")
                .hasCompensated("Room 101", "Room 102");
        // Each cancellation saw the booking id of the room it undid:
        assertThat(runtime.invocationsOf("cancel-room-booking"))
                .extracting(task -> task.variable("roomBookingId"))
                .containsExactlyInAnyOrder("RB-101", "RB-102");
    }

    @Test
    void continueBooksWhatItCanAndReportsTheRest(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);

        var booking = runtime.start("rooms-booking-tolerant", "FAIL-2",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        assertThat(booking).isCompleted()                                 // the step completes anyway
                .hasNoCompensations();
        assertThat(booking).iterations("rooms")
                .hasOnlyFailed("Room 900")
                .hasNoneCompensated()
                .iteration("Room 101").hasOutput("roomBookingId", "RB-101");
        assertThat(booking.variable("roomBookings"))                      // and the output says how each room went
                .contains("{\"status\":\"COMPLETED\",\"value\":\"RB-101\"}", "{\"status\":\"FAILED\",\"error\":\"ERROR\"}");
    }
}
```

- **`hasCompensated("rooms")`** says the rollback undid the step; **`hasNotPassedSteps`** that the guest was never told.
- **`iterations("rooms")`** asserts room by room. An iteration is named by its **label** — the forEach's `label`, `'Room ' + room.number` — by its index (`2`) or by its item; `hasOnlyFailed("Room 900")` means that room failed *and no other did*, and `hasCompensated("Room 101", "Room 102")` that the rollback undid exactly those two. When one of them fails, the message names the rooms the same way (`#2 "Room 900"`), never by an execution id.
- `invocationsOf("cancel-room-booking")` is still the way to check what each compensation was *sent*.

The second test runs a **test-only** variant of module 3 with `onIterationFailure: continue`, from `src/test/flows/rooms-booking-tolerant.yaml` — the same flow, best effort instead of all or none. Writing both tests is how a team *decides* which behaviour a booking should have, and keeps it.

`TaskOutcome.fail(code, false)` is a final failure, as a handler's `TaskFailure(code, reason, false)` would be; `fail(code)` alone is retryable and spends the step's `retries` first.
