package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-20 — resuming a process that actually has somewhere to go.
 *
 * <p>Every other pause/resume test runs on {@code AbstractE2eTest}, which boots the engine in
 * <b>memory</b> mode: no {@code @Version}, no persistence context, no auto-flush. A JPA e2e on H2
 * does not reach it either. The failure only appeared on a real deployment, which is why it is
 * here: real PostgreSQL, real Kafka, the resume arriving as an upstream event and handled on the
 * process-group worker, exactly as in production.
 *
 * <p>Observed there, deterministically, four times out of four:
 *
 * <pre>ObjectOptimisticLockingFailureException: Row was already updated or deleted by another
 *   transaction for entity [ProcessEntity with id '…']
 *   at ProcessDBRepository.save(ProcessDBRepository.java:77)
 *   at ProcessUpdateStepExecutionUpdateUseCase.apply(…:122)
 *   at ResumeProcessUseCase.handle(ResumeProcessUseCase.java:78)</pre>
 *
 * <p>The whole resume rolls back, so the process stays PAUSED after being told to run. Only a
 * resume that had work to release fails — {@code StepOverProcessUseCase} saves the process only
 * when a transition changed it — which is the shape that keeps a bug invisible: every resume that
 * does nothing commits happily.
 *
 * <p>So the step in flight must answer DURING the pause. That leaves its successor held and
 * waiting, and the resume then has something to release.
 */
class Dist20ResumeAdvancingTest extends AbstractDistTest {

    private static final String DEFINITION = "dist-sequential-3";
    private static final String KEY = "resume-advancing";

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
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
    @DisplayName("a resume with a successor to release is not rolled back into PAUSED")
    void resumingAProcessThatHasSomewhereToGo() {
        // s1 answers; s2 is dispatched and deliberately left unanswered, so the process is
        // mid-flight rather than finished when it is paused.
        WorkerStub.on(DEFINITION, "s1", WorkerStub::completeNow);
        WorkerStub.on(DEFINITION, "s2", WorkerStub::silent);

        createProcess(DEFINITION, KEY);
        Awaitility.await("s2 dispatched")
                .atMost(DEFAULT_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> !WorkerStub.receivedFor(processId(KEY)).stream()
                        .filter(request -> "s2".equals(request.stepId())).toList().isEmpty());

        var id = processId(KEY);
        DistInfra.publishUpstream(new PauseProcessRequested(id));
        awaitProcessStatus(KEY, "PAUSED", DEFAULT_TIMEOUT);

        // The in-flight step answers while the process is paused: a pause holds successors, it
        // does not refuse a worker's report. s3 is now waiting to be released.
        var s2 = WorkerStub.receivedFor(id).stream()
                .filter(request -> "s2".equals(request.stepId())).findFirst().orElseThrow();
        WorkerStub.sendStatus(s2.taskExecutionId(), TaskStatus.COMPLETED, List.of(), id);
        Awaitility.await("s2 recorded as completed")
                .atMost(DEFAULT_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> "COMPLETED".equals(stepStatuses(KEY).get("s2")));

        DistInfra.publishUpstream(new ResumeProcessRequested(id));

        // The assertion. Before the fix the resume transaction rolled back on
        // ObjectOptimisticLockingFailureException and the process was still PAUSED two minutes
        // later — a resumed process that did not resume, which is what an operator sees.
        //
        // Completion rather than merely "not PAUSED": what has to work is that the resume RELEASED
        // the held successor. A process that left PAUSED but went nowhere would satisfy the weaker
        // assertion and still be the bug.
        awaitProcessCompleted(KEY);

        assertThat(stepStatuses(KEY)).containsEntry("s3", "COMPLETED");
    }
}
