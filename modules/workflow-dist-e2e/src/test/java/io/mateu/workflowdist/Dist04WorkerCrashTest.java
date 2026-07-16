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

/**
 * DIST-04 — Worker crash / redelivery. The worker takes the first task and never reports
 * back (a worker that died mid-task). The step's timeout (3 s in dist-worker-crash.json)
 * expires, the timeout scheduler marks it TIMEOUT, the retry budget (retries: 1)
 * re-dispatches it, the second execution succeeds and the process completes.
 */
class Dist04WorkerCrashTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
    }

    @Test
    void stepTimesOutAndIsRetriedAfterWorkerDiesMidTask() {
        WorkerStub.on("dist-worker-crash", "work", (request, invocation) -> {
            if (invocation > 1) {
                WorkerStub.complete(request);
            }
            // invocation 1: silence — the worker died mid-task.
        });

        createProcess("dist-worker-crash", "dist04-1");

        awaitProcessCompleted("dist04-1");

        var processId = processId("dist04-1");
        assertThat(WorkerStub.executionCount(processId, "work"))
                .as("first execution lost to the crash, second one completes")
                .isEqualTo(2);
        assertThat(stepStatuses("dist04-1"))
                .containsEntry("work", "COMPLETED")
                .containsEntry("end", "COMPLETED");
        assertThat(completionPercentage("dist04-1")).isEqualTo(100);
    }
}
