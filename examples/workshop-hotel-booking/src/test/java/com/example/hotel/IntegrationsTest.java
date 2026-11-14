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

        assertThat(booking).isCompleted()
                .hasVariable("roomBookings", "[{\"status\": \"COMPLETED\", \"value\": \"H-42\"}]");
        assertThat(hotel.requests()).hasSize(2)
                .allSatisfy(request -> assertThat(request.get("headers"))
                        .isEqualTo(Map.of("Idempotency-Key", "HTTP-1-101")));
    }

    @Test
    void aRoomTheHotelTurnsDownIsReplacedByTheAlternativeTheGuestAccepts(WorkflowTestRuntime runtime) {
        var hotel = runtime.whenHttpCall("rooms").thenRespond(201, "{\"id\": \"H-1\"}");   // every room…
        hotel.whenItemHas("number", "102").thenRespond(409, "sold out");   // …but 102: a 4xx is final
        runtime.whenHttpCall("find-alternatives")
                .thenRespond(200, "[{\"number\": \"104\", \"type\": \"suite\", \"instead\": \"102\"}]");
        var alternatives = runtime.whenHttpCall("book-alternatives").thenRespond(201, "{\"id\": \"H-4\"}");
        runtime.whenStep("ask-guest").thenComplete();

        var booking = runtime.start("rooms-booking-http", "HTTP-2", Map.of("rooms", Rooms.THREE_DOUBLES));
        assertThat(booking).isWaitingAt("wait-for-guest");
        runtime.sendMessage("guest-answer", "HTTP-2", Map.of("answer", "accept"));

        assertThat(booking).isCompleted().hasPassedSteps("booked-with-alternatives").hasNoCompensations();
        assertThat(booking).iterations("rooms").hasOnlyFailed("Room 102").hasNoneCompensated();
        assertThat(alternatives.requests()).singleElement()
                .satisfies(request -> assertThat(String.valueOf(request.get("body"))).contains("104"));
    }

    @Test
    void theGuestCancellingCancelsTheBookedRoomsOverHttp(WorkflowTestRuntime runtime) {
        var hotel = runtime.whenHttpCall("rooms").thenRespond(201, "{\"id\": \"H-1\"}");
        hotel.whenItemHas("number", "102").thenRespond(409, "sold out");
        runtime.whenHttpCall("find-alternatives").thenRespond(200, "[]");
        var cancel = runtime.whenHttpCall("cancel-room").thenRespond(204);
        runtime.whenStep("ask-guest").thenComplete();

        var booking = runtime.start("rooms-booking-http", "HTTP-3", Map.of("rooms", Rooms.THREE_DOUBLES));
        runtime.sendMessage("guest-answer", "HTTP-3", Map.of("answer", "cancel"));

        assertThat(booking).isCompensated().hasCompensated("rooms");
        assertThat(booking).iterations("rooms")
                .hasOnlyFailed("Room 102")
                .hasCompensated("Room 101", "Room 103");
        assertThat(cancel.requests()).extracting(request -> request.get("path"))
                .containsExactlyInAnyOrder("/bookings/by-ref/HTTP-3-101", "/bookings/by-ref/HTTP-3-103");
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
