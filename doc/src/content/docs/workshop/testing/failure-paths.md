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
                .hasPassedSteps("register-booking", "cancel-room-booking")
                .hasNotPassedSteps("notify-guest");
        // The compensation ran once per room that was booked, and saw that room's booking id:
        var cancelled = runtime.invocationsOf("cancel-room-booking").stream()
                .map(task -> task.variable("roomBookingId")).toList();
        assertThat(cancelled).containsExactlyInAnyOrder("RB-101", "RB-102");
    }

    @Test
    void continueBooksWhatItCanAndReportsTheRest(WorkflowTestRuntime runtime) {
        hotelWithASoldOutPenthouse(runtime);

        var booking = runtime.start("rooms-booking-tolerant", "FAIL-2",
                Map.of("guest", "Bob", "rooms", Rooms.WITH_A_PENTHOUSE));

        assertThat(booking).isCompleted();                                // the step completes anyway
        assertThat(runtime.invocationsOf("cancel-room-booking")).isEmpty();
        assertThat(booking.variable("roomBookings"))                      // and says how each room went
                .contains("{\"status\":\"COMPLETED\",\"value\":\"RB-101\"}", "{\"status\":\"FAILED\",\"error\":\"ERROR\"}");
    }
}
```

The second test runs a **test-only** variant of module 3 with `onIterationFailure: continue`, from `src/test/flows/rooms-booking-tolerant.yaml` — the same flow, best effort instead of all or none. Writing both tests is how a team *decides* which behaviour a booking should have, and keeps it.

`TaskOutcome.fail(code, false)` is a final failure, as a handler's `TaskFailure(code, reason, false)` would be; `fail(code)` alone is retryable and spends the step's `retries` first.
