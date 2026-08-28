package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resuming a process that actually has somewhere to go.
 *
 * <p>Every other pause/resume test runs on {@code AbstractE2eTest}, which boots the engine in
 * <b>memory</b> mode: no {@code @Version}, no persistence context, no auto-flush. The failure this
 * pins cannot happen there, which is why it shipped and only appeared on a real deployment.
 *
 * <p>What happens on JPA: {@code ResumeProcessUseCase} saves the process (PAUSED → RUNNING),
 * leaving a version bump pending in the persistence context. {@code StepOverProcessUseCase} then
 * maps the process, runs a derived query for its steps — which auto-flushes that pending update
 * and moves the row to the next version — and saves the object it mapped <i>before</i> the flush.
 * The next query flushes that stale version and Hibernate rejects it:
 *
 * <pre>ObjectOptimisticLockingFailureException: Row was already updated or deleted by another
 * transaction for entity [ProcessEntity with id '…']</pre>
 *
 * <p>The whole resume rolls back, so the process stays PAUSED — and the only resume that fails is
 * one that had work to release, because {@code StepOverProcessUseCase} saves the process only when
 * a transition changed it. A resume that advances nothing commits happily, which is exactly the
 * shape that keeps a bug invisible.
 *
 * <p>Observed on the reference deployment on two processes whose next branch had just become
 * eligible; reproduced here.
 */
class ResumeAdvancingJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired PauseProcessUseCase pauseProcessUseCase;
    @Autowired ResumeProcessUseCase resumeProcessUseCase;
    @Autowired UpdateStepExecutionUseCase updateStepExecutionUseCase;

    @Test
    @DisplayName("a resume with a successor to release completes, and the process runs on")
    void resumingAProcessThatHasSomewhereToGoDoesNotRollBack() {
        // s1 answers, s2 is held: the process is mid-flight rather than finished.
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.deferForever());

        createProcess("sequential-3", "resume-advancing");
        org.awaitility.Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(processOpt("resume-advancing")).isPresent();
            // Dispatched, however the worker has it: deferForever never reports back.
            assertThat(step("resume-advancing", "s2").getStatus())
                    .isIn(StepExecutionStatus.PENDING, StepExecutionStatus.RUNNING);
        });

        var id = process("resume-advancing").getId();
        pauseProcessUseCase.handle(new PauseProcessCommand(id));
        org.awaitility.Awaitility.await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(process("resume-advancing").getStatus()).isEqualTo(ProcessStatus.PAUSED));

        // The in-flight step answers DURING the pause, so its successor is waiting to be released
        // — which is what gives the resume something to advance, and what makes it fail.
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("resume-advancing", "s2").id(), java.util.List.of(), "",
                StepExecutionStatus.COMPLETED));

        // The assertion. Before the fix this threw ObjectOptimisticLockingFailureException out of
        // ProcessUpdateStepExecutionUpdateUseCase and the transaction rolled back.
        resumeProcessUseCase.handle(new ResumeProcessCommand(id));

        // And the rollback is what the status shows: a process that stayed PAUSED after a resume
        // was told to run is the symptom an operator actually sees.
        assertThat(process("resume-advancing").getStatus())
                .as("a resumed process must not be left PAUSED by a rolled-back transaction")
                .isNotEqualTo(ProcessStatus.PAUSED);
    }
}
