package com.example.hotel;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Testing, part d — the hotel's API, a language model and an agent, all simulated. */
@EventConductorTest(definitions = "src/test/flows")
class IntegrationsTest {

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
}
