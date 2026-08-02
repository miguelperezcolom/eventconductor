package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

/**
 * DIST-12 — One event the engine cannot process must not stop the ones around it.
 *
 * <p>A worker reporting on a task the engine has never heard of — a stale id after a redeploy, a
 * misconfigured worker — makes the handler throw. Handled one event at a time that is logged and
 * dropped, and everything else carries on.
 *
 * <p>It stops being harmless the moment a whole poll batch shares a transaction: a single
 * unprocessable event marks that transaction rollback-only, so the good events committed beside
 * it are rolled back too, redelivered, and poisoned again. This drives real traffic with one such
 * event mixed into it and asserts the real traffic still finishes.
 */
class Dist12PoisonEventTest extends AbstractDistTest {

    static final int PROCESSES = 12;

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPod() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void anUnprocessableEventDoesNotStallTheTrafficAroundIt() {
        for (var i = 0; i < PROCESSES; i++) {
            createProcess("dist-sequential-3", "dist12-" + i);
            if (i == PROCESSES / 2) {
                // A report for a step execution that does not exist: the use case looks it up and
                // throws. Published mid-burst so it shares a poll batch with real work.
                DistInfra.publishUpstream(new TaskStatusChanged(
                        "no-such-step-execution", TaskStatus.COMPLETED, List.of(), "no-such-process"));
            }
        }

        for (var i = 0; i < PROCESSES; i++) {
            awaitProcessCompleted("dist12-" + i);
        }
    }
}
