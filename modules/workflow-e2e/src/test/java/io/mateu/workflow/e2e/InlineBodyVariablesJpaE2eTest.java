package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.mateu.workflow.e2e.InlineBodyVariablesE2eTest.ROOMS;
import static io.mateu.workflow.e2e.InlineBodyVariablesE2eTest.rendered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link InlineBodyVariablesE2eTest} through the database: what a child inherits from its forEach item
 * and from the process that owns its inline body (inherited_business_key, structured_variables) survives
 * the round trip, so every step of the body — and its compensation — renders with it.
 */
class InlineBodyVariablesJpaE2eTest extends AbstractJpaE2eTest {

    @Test
    void theBodysTemplatesSeeTheRoomTheIndexAndTheBookingsKey() {
        var creates = new CopyOnWriteArrayList<JsonNode>();
        worker.on("create", (req, cb, n) -> {
            creates.add(rendered(req));
            cb.complete(List.of(TestWorker.var("roomBookingId", "B-" + creates.size())));
        });
        worker.on("check", TestWorker.succeed());

        createProcess("inline-body-vars", "JBOOKING-1", new Variable("rooms", ROOMS));

        awaitStatus("JBOOKING-1", ProcessStatus.COMPLETED);
        assertThat(creates).extracting(r -> r.path("headers").path("Idempotency-Key").asText())
                .containsExactlyInAnyOrder("JBOOKING-1-101", "JBOOKING-1-102");
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

        createProcess("inline-body-vars", "JBOOKING-2", new Variable("rooms", ROOMS));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(releases).containsExactlyInAnyOrder(
                "https://hotel.example.com/holds/JBOOKING-2-101-0", "https://hotel.example.com/holds/JBOOKING-2-102-1"));
    }
}
