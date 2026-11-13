---
title: "9a · The happy path"
description: Test the booking when everything goes right — mocks for the tasks, an answer per room, and assertions on the steps and the collected output.
---

From `HappyPathTest.java`:

```java
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
```

- **`whenStep(id)`** mocks a step's task: `thenComplete(variables)`, `thenFail(code)`, `thenTimeout()`, or `thenAnswer(task -> …)` to decide from what the task was sent. A mock matches the step wherever it runs — here, inside each of the forEach's three child runs.
- **`start(definition, businessKey, variables)`** returns once the engine has done all it can: there is no `await`. Give each test its own business key.
- **`assertThat(process)`** (from `WorkflowAssertions`) reads the process when it runs: its status, the steps that passed, its variables — `roomBookings` is the forEach's `output`, one element per room, in room order.
- **`invocationsOf(step)`** lists every dispatch of a step: what it was sent, which attempt, at what (engine) time.

The test data is a JSON array, exactly as the REST API would carry it:

```java
static final String THREE_DOUBLES = """
        [{"number": "101", "type": "double"},
         {"number": "102", "type": "double"},
         {"number": "103", "type": "double"}]""";
```
