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

The stubbed answer goes through the step's `output` expressions (`json.category`) — so the test checks the mapping, and `lastRequest()` checks the prompt the engine actually rendered. (The kit does not validate the answer against `responseSchema`.)

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
