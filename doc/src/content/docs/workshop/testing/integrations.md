---
title: "9d · Simulated integrations"
description: Test HTTP calls, language model calls and agents without the network — including the idempotency key the hotel receives and an agent held for human review.
---

An `HTTP_CALL`, `LLM_CALL` or `AGENT` step **never reaches the network in a test**: without a stub it fails at once, with a message naming the stub to add. With one, the engine renders the request exactly as in production, and the stubbed response goes through the step's own `successStatus`, `retryOn` and `output` mapping. From `IntegrationsTest.java`:

## The hotel's API — and the key it receives

```java
@Test
void sendsTheSameIdempotencyKeyOnEveryAttempt(WorkflowTestRuntime runtime) {
    var hotel = runtime.whenHttpCall("rooms")
            .thenRespond(503, "busy")                                 // retried: 5xx is in retryOn
            .thenRespond(201, "{\"id\": \"H-42\"}");

    var booking = runtime.start("rooms-booking-http", "HTTP-1",
            Map.of("rooms", "[{\"number\": \"101\", \"type\": \"double\"}]"));
    runtime.advanceTime(java.time.Duration.ofMinutes(1));

    assertThat(booking).isCompleted().hasVariable("roomBookings", "[\"H-42\"]");
    assertThat(hotel.requests()).hasSize(2)
            .allSatisfy(request -> assertThat(request.get("headers"))
                    .isEqualTo(Map.of("Idempotency-Key", "HTTP-1-101")));
}
```

The hotel is busy once, then books the room. The test checks what matters for not booking twice: **both attempts carried the same `Idempotency-Key`**, built from the business key and the room. `hotel.requests()` is every request the step rendered — method, path, headers, body.

## One room the hotel turns down

The step makes one call per room. A **scoped** stub answers some of them apart from the others — by iteration (`whenIteration(1)`), by item (`whenItemHas("number", "102")`) or by request (`whenRequest(…)`) — with answers of its own:

```java
@Test
void aRoomTheHotelTurnsDownUndoesTheOthers(WorkflowTestRuntime runtime) {
    var hotel = runtime.whenHttpCall("rooms").thenRespond(201, "{\"id\": \"H-1\"}");   // every room…
    hotel.whenItemHas("number", "102").thenRespond(409, "sold out");   // …but 102: a 4xx is final
    var cancel = runtime.whenHttpCall("cancel-room").thenRespond(204);

    var booking = runtime.start("rooms-booking-http", "HTTP-2", Map.of("rooms", Rooms.THREE_DOUBLES));

    assertThat(booking).isCompensated().hasCompensated("rooms");
    assertThat(booking).iterations("rooms")
            .hasOnlyFailed("Room 102")
            .hasCompensated("Room 101", "Room 103");
    assertThat(cancel.requests()).extracting(request -> request.get("path"))
            .containsExactlyInAnyOrder("/bookings/by-ref/HTTP-2-101", "/bookings/by-ref/HTTP-2-103");
}
```

Room 102 is refused, `compensateAll` waits for the other two and then undoes them — over HTTP too: the `DELETE`s went to exactly the two rooms that were booked. Each scope consumes (and repeats) its own answers, so `hotel.whenIteration(0).thenRespond(503, "busy").thenRespond(201, …)` would make *that* room retry while the others are booked at once.

## The language model

```java
@Test
void classifiesTheRequestAndDraftsTheEmail(WorkflowTestRuntime runtime) {
    var classify = runtime.whenLlmCall("classify-request")
            .thenAnswer("{\"category\": \"accessibility\", \"needsStaff\": true, \"summary\": \"wheelchair\"}");
    runtime.whenLlmCall("draft-confirmation").thenAnswer("Dear Erin, your booking is confirmed.");
    runtime.whenStep("notify-guest").thenComplete();

    var booking = runtime.start("special-request", "LLM-1", Map.of("guest", "Erin",
            "specialRequest", "I use a wheelchair", "rooms", Rooms.THREE_DOUBLES));

    assertThat(booking).isCompleted()
            .hasVariable("requestCategory", "accessibility")
            .hasVariable("confirmationEmail", "Dear Erin, your booking is confirmed.");
    assertThat(String.valueOf(classify.lastRequest().get("prompt"))).contains("I use a wheelchair");
}
```

The stubbed answer goes through the step's `output` expressions (`json.category`) — so the test checks the mapping, and `lastRequest()` checks the prompt the engine actually rendered.

The stub is **validated against the step's `responseSchema`**, as a real model's answer is. Stub `{"category": "spa"}` and the test fails, saying what the schema refuses — `category` is not in the enum, `needsStaff` and `summary` are missing — instead of passing on an answer production would never accept. To test what happens when the model *does* answer badly, say so: `thenInvalidAnswer("…")` fails the call with `LLM_INVALID_OUTPUT`, and the step retries as it would in production.

## An agent, held for review

`AGENT` cannot run in the workshop app (it needs the IA catalogue), but it runs in a test: the kit plays both the catalogue and the agent runtime. The definition is test-only, in `src/test/flows/special-request-agent.yaml`.

```java
@Test
void anAgentCanBeHeldForHumanReview(WorkflowTestRuntime runtime) {
    runtime.whenAgent("prepare-request")
            .thenReviewRequired()
            .thenOutput("{\"actions\": [\"reserve an accessible room\"]}");

    var booking = runtime.start("special-request-agent", "AGENT-1",
            Map.of("guest", "Erin", "specialRequest", "I use a wheelchair"));
    assertThat(booking).hasStatus(ProcessStatus.PAUSED)
            .hasStepStatus("prepare-request", StepExecutionStatus.REVIEW_REQUIRED);

    runtime.resolveAgentReview(booking, "prepare-request");
    assertThat(booking).isCompleted().hasVariable("preparation");
}
```

The process **pauses** while the agent's outcome is under review, and goes on only once the runtime resolves it.

## Messages, events and updates

The hotel booking does not wait for messages or accept updates, so it needs none of these — but when your process does, the kit drives them the same way: `sendMessage(name, correlationKey, variables)`, `publishEvent(destination, type, data)`, and `update(process, "changeDates", payload)` with assertions on the outcome. See [Testing Workflows](/guides/testing-workflows/#messages-events-and-replies).
