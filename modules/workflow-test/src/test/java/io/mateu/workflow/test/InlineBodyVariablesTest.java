package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the steps of an inline PROCESS body under a {@code forEach} see: the item as a structure
 * ({@code ${room.number}}), its index, and — in an inline body only — the business key of the process
 * that owns the body, not the child's generated {@code parent:<id>}.
 */
@EventConductorTest
class InlineBodyVariablesTest {

    private static final List<Map<String, Object>> ROOMS = List.of(
            Map.of("number", 101, "type", "double"),
            Map.of("number", 102, "type", "suite"));

    @Test
    void httpCallsInsideTheBodySeeTheRoomTheIndexAndTheBookingsBusinessKey(WorkflowTestRuntime runtime) {
        var create = runtime.whenHttpCall("create").thenRespond(201, "{\"id\":\"B-1\"}");
        var check = runtime.whenHttpCall("check").thenRespond(200);
        runtime.whenTask("approve").thenComplete();

        var booking = runtime.start("rooms-inline-http", "BOOKING-1", Map.of("rooms", ROOMS));

        assertThat(booking).isCompleted();
        assertThat(create.requests()).hasSize(2);
        assertThat(create.requests()).extracting(request -> header(request, "Idempotency-Key"))
                .containsExactlyInAnyOrder("BOOKING-1-101", "BOOKING-1-102");
        assertThat(create.requests()).extracting(request -> body(request).get("externalRef") + "|"
                        + body(request).get("room") + "|" + body(request).get("position") + "|" + body(request).get("type"))
                .containsExactlyInAnyOrder("BOOKING-1-101|101|0|double", "BOOKING-1-102|102|1|suite");
        assertThat(check.requests()).extracting(request -> String.valueOf(request.get("url")))
                .containsExactlyInAnyOrder("https://hotel.example.com/bookings/B-1/check?room=101",
                        "https://hotel.example.com/bookings/B-1/check?room=102");
    }

    @Test
    void aCompensationInsideTheBodySeesThemToo(WorkflowTestRuntime runtime) {
        runtime.whenHttpCall("create").thenRespond(201, "{\"id\":\"B-1\"}");
        // The check fails for good in every room: each child compensates its create.
        runtime.whenHttpCall("check").thenRespond(404, "gone");
        var release = runtime.whenHttpCall("release").thenRespond(204);
        var cancel = runtime.whenHttpCall("cancelRoom").thenRespond(204);

        var booking = runtime.start("rooms-inline-http", "BOOKING-2", Map.of("rooms", ROOMS));

        assertThat(booking.status().name()).isIn("COMPENSATED", "ERROR");
        assertThat(release.requests()).extracting(request -> String.valueOf(request.get("url")))
                .containsExactlyInAnyOrder("https://hotel.example.com/holds/BOOKING-2-101-0",
                        "https://hotel.example.com/holds/BOOKING-2-102-1");
        assertThat(cancel.requests()).isEmpty();
    }

    @Test
    void theChildKeepsItsOwnBusinessKey(WorkflowTestRuntime runtime) {
        runtime.whenHttpCall("create").thenRespond(201, "{\"id\":\"B-1\"}");
        runtime.whenHttpCall("check").thenRespond(200);
        runtime.whenTask("approve").thenComplete();

        runtime.start("rooms-inline-http", "BOOKING-3", Map.of("rooms", ROOMS));

        assertThat(runtime.processes("rooms-inline-http.rooms"))
                .allSatisfy(child -> assertThat(child.state().getBusinessKey()).startsWith("parent:"));
    }

    @Test
    void aNamedChildReadsTheRoomTooButKeepsItsOwnBusinessKey(WorkflowTestRuntime runtime) {
        var hold = runtime.whenHttpCall("hold").thenRespond(201);

        var booking = runtime.start("rooms-named-http", "BOOKING-4", Map.of("rooms", ROOMS));

        assertThat(booking).isCompleted();
        assertThat(hold.requests()).extracting(request -> String.valueOf(request.get("url")))
                .hasSize(2)
                .allSatisfy(url -> assertThat(url).contains("ref=parent:"))
                .anySatisfy(url -> assertThat(url).startsWith("https://hotel.example.com/holds/101?").endsWith("&index=0"))
                .anySatisfy(url -> assertThat(url).startsWith("https://hotel.example.com/holds/102?").endsWith("&index=1"));
    }

    /** The rendered JSON body (the request carries it as text). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<String, Object> request) {
        var body = request.get("body");
        if (body instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(body), Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String header(Map<String, Object> request, String name) {
        return String.valueOf(((Map<String, Object>) request.get("headers")).get(name));
    }
}
