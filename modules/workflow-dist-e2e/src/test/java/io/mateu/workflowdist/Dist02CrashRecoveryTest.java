package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-02 — Orchestrator crash recovery through the outbox. The orchestrator is killed
 * after a step's completion is committed but before the resulting domain event is
 * dispatched: the test holds the relay's PostgreSQL advisory lock, so the
 * StepExecutionStatusChanged row is committed as Pending and cannot be relayed — the exact
 * durable state a crash between "commit" and "publish" leaves behind. The pod is then
 * killed abruptly, a fresh pod is started, and the process must resume from the outbox and
 * complete.
 */
class Dist02CrashRecoveryTest extends AbstractDistTest {

    ConfigurableApplicationContext orchestrator;

    @AfterEach
    void stopPods() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void processResumesFromOutboxAfterOrchestratorCrash() throws Exception {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());

        // s1 does not auto-complete: the test decides when the worker reports back.
        var s1Request = new AtomicReference<TaskExecutionRequested>();
        WorkerStub.on("dist-crash-recovery", "s1", (request, invocation) -> s1Request.set(request));

        createProcess("dist-crash-recovery", "dist02-1");
        await("worker received s1").atMost(DEFAULT_TIMEOUT).until(() -> s1Request.get() != null);
        var processId = processId("dist02-1");

        // Freeze the outbox relay, then let s1 complete: the step's COMPLETED state and its
        // StepExecutionStatusChanged outbox row are committed, but nothing can be dispatched.
        var relayLock = DistInfra.blockOutboxRelay();
        try {
            WorkerStub.complete(s1Request.get());
            await("s1 committed as COMPLETED").atMost(DEFAULT_TIMEOUT)
                    .until(() -> "COMPLETED".equals(stepStatuses("dist02-1").get("s1")));
            await("undispatched event parked in the outbox").atMost(DEFAULT_TIMEOUT)
                    .until(() -> pendingOutboxMessages() > 0);

            // Kill the pod: crash window = step completed, next step not yet dispatched.
            orchestrator.close();
            orchestrator = null;
        } finally {
            DistInfra.unblockOutboxRelay(relayLock);
        }

        assertThat(WorkerStub.executionCount(processId, "s2")).isZero();
        assertThat(processStatus("dist02-1")).isNotEqualTo(java.util.Optional.of("COMPLETED"));
        assertThat(pendingOutboxMessages()).isGreaterThan(0);

        // Restart: the new pod's relay must pick the Pending rows up and drive the process home.
        orchestrator = DistInfra.startOrchestrator(Map.of());

        awaitProcessCompleted("dist02-1");
        assertThat(stepStatuses("dist02-1"))
                .containsEntry("s1", "COMPLETED")
                .containsEntry("s2", "COMPLETED")
                .containsEntry("end", "COMPLETED");
        assertThat(WorkerStub.executionCount(processId, "s1")).isEqualTo(1);
        assertThat(WorkerStub.executionCount(processId, "s2")).isEqualTo(1);
        await("outbox drained after recovery").atMost(DEFAULT_TIMEOUT)
                .until(() -> pendingOutboxMessages() == 0);
    }
}
