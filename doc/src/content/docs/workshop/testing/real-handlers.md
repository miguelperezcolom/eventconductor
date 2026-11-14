---
title: "9e · The real handlers"
description: Run your own task handlers inside the test, and replace one of them with a mock when you need to.
---

Mocks test the **definition**. To test the definition *with your workers*, boot them too: they run in-process through the worker SDK, exactly as in the application. A task no mock answers is answered by the real handler. From `RealHandlersTest.java`:

```java
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
```

- `classes = {WorkflowTestApplication.class, HotelTaskHandlers.class}` adds the handlers' configuration to the kit's engine. (The workshop does not boot the whole `HotelBookingApplication` here: the console and the fakes are not needed to test the process.)
- The second test is the penthouse failure of module 3, with **the real handler** refusing it — the same thing you saw in the console, in milliseconds.
- A **mock always wins** over a handler, so one dependency — the mail server — can be made to fail while the rest stays real.
