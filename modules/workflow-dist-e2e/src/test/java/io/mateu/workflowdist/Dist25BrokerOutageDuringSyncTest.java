package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.SyncCalls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-25 — the broker goes away during a synchronous invocation. The invocation is accepted (the
 * creation needs only the database), the caller is answered 202 at its deadline, and once the
 * broker is back the process finishes and the reply is there for the caller's GET. Nothing lost,
 * nothing dead-lettered.
 */
class Dist25BrokerOutageDuringSyncTest extends AbstractDistTest {

    static ConfigurableApplicationContext pod;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        pod = DistInfra.startOrchestrator(Map.of("workflow.sync.max-deadline-ms", "60000"));
    }

    @AfterAll
    static void stopPods() {
        pod.close();
    }

    @AfterEach
    void kafkaBack() {
        DistInfra.resumeKafka();
    }

    @Test
    void theReplyArrivesOnceTheBrokerIsBack() throws Exception {
        DistInfra.pauseKafka();
        var view = SyncCalls.invoke(pod, "dist-sync-workers", "dist25-key", Map.of("orderId", "O-25"),
                Duration.ofSeconds(3));
        assertThat(view.replied()).as("answered 202 at the deadline, broker down").isFalse();

        DistInfra.resumeKafka();
        await().atMost(Duration.ofSeconds(180)).untilAsserted(() ->
                assertThat(SyncCalls.byKey(pod, "dist-sync-workers", "dist25-key").replied()).isTrue());
        assertThat(SyncCalls.byKey(pod, "dist-sync-workers", "dist25-key").reply().payload()).contains("O-25");
        await().atMost(Duration.ofSeconds(60)).until(() -> pendingOutboxMessages() == 0);
        assertThat(DistInfra.jdbc().queryForObject(
                "select count(*) from outbox_message_entity where status in ('Error', 'InlineClaimed')", Integer.class))
                .isZero();
    }
}
