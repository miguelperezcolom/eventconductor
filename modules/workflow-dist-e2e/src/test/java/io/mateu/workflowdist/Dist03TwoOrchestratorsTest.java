package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-03 — Two orchestrator pods. Both instances join the orchestrator consumer group on
 * the multi-partition upstream/outbox topics, so events of the same process are handled by
 * either pod concurrently. PostgreSQL advisory locks (per-process lock + the relay's global
 * lock) must guarantee each step is dispatched exactly once — asserted on the worker-side
 * execution count per (process, step).
 */
class Dist03TwoOrchestratorsTest extends AbstractDistTest {

    static final int PROCESSES = 20;

    static ConfigurableApplicationContext orchestrator1;
    static ConfigurableApplicationContext orchestrator2;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        orchestrator1 = DistInfra.startOrchestrator(Map.of());
        orchestrator2 = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator1.close();
        orchestrator2.close();
    }

    @Test
    void advisoryLocksGuaranteeExactlyOneDispatchPerStep() {
        for (int i = 0; i < PROCESSES; i++) {
            createProcess("dist-sequential-3", "dist03-" + i);
        }

        await("all " + PROCESSES + " processes completed").atMost(DEFAULT_TIMEOUT)
                .until(() -> DistInfra.jdbc().queryForObject(
                        "SELECT count(*) FROM process_entity WHERE business_key LIKE 'dist03-%' AND status = 'COMPLETED'",
                        Integer.class) == PROCESSES);

        for (int i = 0; i < PROCESSES; i++) {
            var businessKey = "dist03-" + i;
            var processId = processId(businessKey);
            for (var stepId : new String[]{"s1", "s2", "s3"}) {
                assertThat(WorkerStub.executionCount(processId, stepId))
                        .as("worker executions of %s for %s", stepId, businessKey)
                        .isEqualTo(1);
            }
            assertThat(stepStatuses(businessKey))
                    .containsEntry("s1", "COMPLETED")
                    .containsEntry("s2", "COMPLETED")
                    .containsEntry("s3", "COMPLETED")
                    .containsEntry("end", "COMPLETED");
        }
    }
}
