package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The steps of an inline PROCESS body under a {@code forEach} see the item as a structure
 * ({@code ${room.number}}), its index, and the business key of the process that owns the body — in
 * the body's HTTP_CALLs and in a compensation inside the body. Memory mode; see
 * {@link InlineBodyVariablesJpaE2eTest} for the same through the database.
 */
class InlineBodyVariablesE2eTest extends AbstractE2eTest {

    static final String ROOMS = "[{\"number\":101},{\"number\":102}]";
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theBodysTemplatesSeeTheRoomTheIndexAndTheBookingsKey() {
        var creates = new CopyOnWriteArrayList<JsonNode>();
        worker.on("create", (req, cb, n) -> {
            creates.add(rendered(req));
            cb.complete(List.of(TestWorker.var("roomBookingId", "B-" + creates.size())));
        });
        worker.on("check", TestWorker.succeed());

        createProcess("inline-body-vars", "BOOKING-1", new Variable("rooms", ROOMS));

        assertThat(process("BOOKING-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(creates).extracting(r -> r.path("headers").path("Idempotency-Key").asText())
                .containsExactlyInAnyOrder("BOOKING-1-101", "BOOKING-1-102");
        assertThat(creates).extracting(r -> r.path("body").asText())
                .containsExactlyInAnyOrder("{\"room\":101,\"position\":\"0\"}", "{\"room\":102,\"position\":\"1\"}");
    }

    @Test
    void aCompensationInsideTheBodySeesThemToo() {
        worker.on("create", TestWorker.succeed());
        worker.on("check", (req, cb, n) -> cb.fail("no availability"));
        var releases = new CopyOnWriteArrayList<String>();
        worker.on("release", (req, cb, n) -> {
            releases.add(rendered(req).path("url").asText());
            cb.complete();
        });

        createProcess("inline-body-vars", "BOOKING-2", new Variable("rooms", ROOMS));

        assertThat(releases).containsExactlyInAnyOrder("https://hotel.example.com/holds/BOOKING-2-101-0",
                "https://hotel.example.com/holds/BOOKING-2-102-1");
    }

    /** The request the engine rendered for an HTTP_CALL step (task variable {@code __http}). */
    static JsonNode rendered(TaskExecutionRequested request) {
        var http = request.variables().stream().filter(v -> "__http".equals(v.name())).findFirst().orElseThrow();
        try {
            return JSON.readTree(http.value());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
