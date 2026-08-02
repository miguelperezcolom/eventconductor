package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-09 — Several relays drain the outbox at once.
 *
 * <p>The relay used to be a leader-elected singleton: one pod drained everything while the rest
 * idled, so relay throughput did not grow with the cluster even though every state transition in
 * kafka mode goes through it. Pods now claim batches with {@code FOR UPDATE SKIP LOCKED} instead.
 *
 * <p>This has to run against real PostgreSQL. H2 — where the JPA e2e suite runs — accepts the
 * same statement but locks every row the query matches rather than only the ones it returns, so a
 * second claimer there comes back empty and the property under test is invisible.
 */
class Dist09ConcurrentOutboxClaimTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator1;
    static ConfigurableApplicationContext orchestrator2;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        // Two real relays running against the schema they create — both gated for the duration
        // of the claim so they do not race the test for its rows.
        orchestrator1 = DistInfra.startOrchestrator(Map.of());
        orchestrator2 = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator1.close();
        orchestrator2.close();
    }

    private static final String CLAIM =
            "SELECT id FROM outbox_message_entity WHERE status = 'Pending' "
                    + "ORDER BY timestamp LIMIT ? FOR UPDATE SKIP LOCKED";

    @Test
    void twoPodsClaimDisjointBatchesWithoutBlockingEachOther() throws Exception {
        // The running orchestrators must not race us for these rows.
        var gate = DistInfra.blockOutboxRelay();
        var ids = new ArrayList<String>();
        Connection podA = null;
        Connection podB = null;
        try {
            for (var i = 0; i < 6; i++) {
                var id = "dist09-" + UUID.randomUUID();
                ids.add(id);
                DistInfra.jdbc().update(
                        "INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload) "
                                + "VALUES (?, CURRENT_TIMESTAMP, 'Pending', 'dist09', '{}')", id);
            }

            podA = DistInfra.newSession();
            podB = DistInfra.newSession();

            var start = System.currentTimeMillis();
            var claimedByA = claim(podA, 3);
            var claimedByB = claim(podB, 3);
            var elapsed = System.currentTimeMillis() - start;

            assertThat(claimedByA).as("first pod claims a batch").hasSize(3);
            assertThat(claimedByB).as("second pod claims too — it is not shut out by a leader")
                    .hasSize(3);
            assertThat(claimedByA).as("the batches must not overlap: a message relayed twice")
                    .doesNotContainAnyElementsOf(claimedByB);
            assertThat(elapsed).as("SKIP LOCKED skips, it does not wait").isLessThan(5_000);

            // Everything is claimed, so a third pod finds nothing rather than duplicating work.
            try (var podC = DistInfra.newSession()) {
                assertThat(claim(podC, 3)).isEmpty();
            }
        } finally {
            if (podA != null) { podA.rollback(); podA.close(); }
            if (podB != null) { podB.rollback(); podB.close(); }
            try {
                ids.forEach(id -> DistInfra.jdbc().update(
                        "DELETE FROM outbox_message_entity WHERE id = ?", id));
            } catch (RuntimeException cleanupFailure) {
                // Never let cleanup mask the assertion that actually failed.
                cleanupFailure.printStackTrace();
            }
            DistInfra.unblockOutboxRelay(gate);
        }
    }

    private List<String> claim(Connection session, int batchSize) throws Exception {
        var claimed = new ArrayList<String>();
        try (var ps = session.prepareStatement(CLAIM)) {
            ps.setInt(1, batchSize);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.add(rs.getString(1));
                }
            }
        }
        return claimed;
    }
}
