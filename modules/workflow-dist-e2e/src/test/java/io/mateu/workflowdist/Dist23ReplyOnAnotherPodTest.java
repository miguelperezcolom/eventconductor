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

/**
 * DIST-23 — the reply is recorded on another pod. Two orchestrator pods; every invocation lands on
 * the first, while the Kafka workers' replies are consumed by whichever pod owns the process's
 * partition — the second about half the time — and it is that pod that reaches the REPLY. The
 * waiters' poll is set to ten seconds, so a caller answered well inside that heard through
 * PostgreSQL NOTIFY (or locally), not by polling.
 */
class Dist23ReplyOnAnotherPodTest extends AbstractDistTest {

    static ConfigurableApplicationContext podA;
    static ConfigurableApplicationContext podB;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        var props = Map.<String, Object>of("workflow.sync.poll-interval-ms", "10000",
                "workflow.sync.max-deadline-ms", "60000");
        podA = DistInfra.startOrchestrator(props);
        podB = DistInfra.startOrchestrator(props);
    }

    @AfterAll
    static void stopPods() {
        podA.close();
        podB.close();
    }

    @Test
    void everyCallerHearsPromptlyWhicheverPodRecordsTheReply() {
        for (int i = 0; i < 20; i++) {
            var key = "dist23-" + i + "-" + UUID.randomUUID();
            var started = System.nanoTime();
            var view = SyncCalls.invoke(podA, "dist-sync-workers", key, Map.of("orderId", key), Duration.ofSeconds(30));
            var millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(view.replied()).as("invocation %d replied", i).isTrue();
            assertThat(millis).as("invocation %d answered without waiting for the 10 s poll", i).isLessThan(5_000);
        }
    }
}
