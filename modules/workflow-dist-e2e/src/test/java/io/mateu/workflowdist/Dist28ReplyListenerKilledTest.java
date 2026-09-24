package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.SyncCalls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-28 — the NOTIFY listener's connection is killed by the database. Callers are still answered
 * (by the poll while the listener is down), and the listener reconnects on its own.
 */
class Dist28ReplyListenerKilledTest extends AbstractDistTest {

    static ConfigurableApplicationContext podA;
    static ConfigurableApplicationContext podB;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        var props = Map.<String, Object>of("workflow.sync.poll-interval-ms", "1000",
                "workflow.sync.max-deadline-ms", "60000");
        podA = DistInfra.startOrchestrator(props);
        podB = DistInfra.startOrchestrator(props);
    }

    @AfterAll
    static void stopPods() {
        podA.close();
        podB.close();
    }

    private static int listeners() {
        return DistInfra.jdbc().queryForObject(
                "select count(*) from pg_stat_activity where query ilike 'LISTEN ec_sync_reply%'", Integer.class);
    }

    @Test
    void callersAreAnsweredAndTheListenerComesBack() {
        await().atMost(Duration.ofSeconds(30)).until(() -> listeners() >= 2);

        DistInfra.jdbc().queryForList(
                "select pg_terminate_backend(pid) from pg_stat_activity where query ilike 'LISTEN ec_sync_reply%'");

        for (int i = 0; i < 5; i++) {
            var key = "dist28-" + i + "-" + UUID.randomUUID();
            var view = SyncCalls.invoke(podA, "dist-sync-workers", key, Map.of("orderId", key), Duration.ofSeconds(30));
            assertThat(view.replied()).as("invocation %d replied with the listener down or reconnecting", i).isTrue();
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> listeners() >= 2);
    }
}
