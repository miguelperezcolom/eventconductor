package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.SyncCalls;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-27 — the inline drive and the partition owner write the same process. Two parallel Kafka
 * workers: the pod that took the request dispatches both inline while the first worker's reply may
 * already be landing on the partition owner — two writers on one process for a moment. The engine's
 * answer is optimistic versioning (the loser retries, or the drive gives its rows back to the relay);
 * what must hold is that every step ran exactly once and every caller got its reply.
 */
class Dist27InlineAgainstPartitionOwnerTest extends AbstractDistTest {

    static ConfigurableApplicationContext podA;
    static ConfigurableApplicationContext podB;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        var props = Map.<String, Object>of("workflow.sync.max-deadline-ms", "60000");
        podA = DistInfra.startOrchestrator(props);
        podB = DistInfra.startOrchestrator(props);
    }

    @AfterAll
    static void stopPods() {
        podA.close();
        podB.close();
    }

    @Test
    void everyStepRunsOnceAndEveryCallerIsAnswered() {
        for (int i = 0; i < 20; i++) {
            var key = "dist27-" + i + "-" + UUID.randomUUID();
            var view = SyncCalls.invoke(podA, "dist-sync-fork", key, Map.of("orderId", key), Duration.ofSeconds(30));
            assertThat(view.replied()).as("invocation %d replied", i).isTrue();
            var processId = view.invocation().processId();
            assertThat(WorkerStub.executionCount(processId, "a")).as("a ran once").isEqualTo(1);
            assertThat(WorkerStub.executionCount(processId, "b")).as("b ran once").isEqualTo(1);
        }
    }
}
