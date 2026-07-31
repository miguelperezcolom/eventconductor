package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.lifecycle.PauseWorkflowUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ResumeWorkflowUseCase;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-PAUSE-01..04 — pause/play for processes and workflow definitions: a paused process
 * accepts in-flight completions but holds successors and freezes timer/timeout clocks; a
 * paused definition holds all its processes and new instances are born paused.
 */
class PauseResumeE2eTest extends AbstractE2eTest {

    @Autowired PauseProcessUseCase pauseProcessUseCase;
    @Autowired ResumeProcessUseCase resumeProcessUseCase;
    @Autowired PauseWorkflowUseCase pauseWorkflowUseCase;
    @Autowired ResumeWorkflowUseCase resumeWorkflowUseCase;
    @Autowired WorkflowDefinitionRepository workflowDefinitionRepository;

    /** The classpath definitions are shared by all e2e tests — never leave one paused. */
    @AfterEach
    void unpauseSharedDefinitions() {
        for (var definitionId : List.of("sequential-3", "timer", "message")) {
            if (workflowDefinitionRepository.findById(definitionId).orElseThrow().paused()) {
                resumeWorkflowUseCase.handle(definitionId);
            }
        }
    }

    private void pause(String businessKey) {
        pauseProcessUseCase.handle(new PauseProcessCommand(process(businessKey).getId()));
    }

    private void resume(String businessKey) {
        resumeProcessUseCase.handle(new ResumeProcessCommand(process(businessKey).getId()));
    }

    private void sendMessage(String messageName, String correlationKey, Variable... variables) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new MessageReceived(messageName, correlationKey, List.of(variables))));
    }

    private void completeDeferredStep(String businessKey, String stepId) {
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step(businessKey, stepId).id(), List.of(), "", StepExecutionStatus.COMPLETED));
    }

    /** E2E-PAUSE-01 — pause mid-flight: an in-flight task may complete, its successor is held. */
    @Test
    void taskCompletedDuringPauseDoesNotStartItsSuccessorUntilResume() {
        worker.on("s1", TestWorker.deferForever());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "pz-1");
        assertThat(step("pz-1", "s1").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        pause("pz-1");
        assertThat(process("pz-1").getStatus()).isEqualTo(ProcessStatus.PAUSED);
        assertThat(process("pz-1").getPausedAt()).isNotNull();

        // The worker report is accepted while paused: the step completes...
        completeDeferredStep("pz-1", "s1");
        assertThat(step("pz-1", "s1").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // ...but the successor does NOT start and the process stays PAUSED.
        assertThat(step("pz-1", "s2").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(worker.invocationsOf("s2")).isZero();
        assertThat(process("pz-1").getStatus()).isEqualTo(ProcessStatus.PAUSED);

        resume("pz-1");
        assertThat(process("pz-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("pz-1").getPausedAt()).isNull();
        assertThat(worker.invocationsOf("s2")).isEqualTo(1);
        assertThat(worker.invocationsOf("s3")).isEqualTo(1);
    }

    /** E2E-PAUSE-02 — frozen timer: a due TIMER does not fire while paused; on resume the due moment is shifted. */
    @Test
    void pausedTimerDoesNotFireUntilResumedAndThenFiresAfterTheShiftedDue() {
        worker.on("after", TestWorker.succeed());

        createProcess("timer", "pz-2");
        assertThat(step("pz-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        var originalStartedAt = step("pz-2", "wait").getStartedAt();

        pause("pz-2");

        // Wait well beyond the original due moment (PT0.5S duration, 200ms scan): the timer
        // clock is frozen, so the step must still be PENDING.
        await().pollDelay(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(step("pz-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        assertThat(process("pz-2").getStatus()).isEqualTo(ProcessStatus.PAUSED);

        resume("pz-2");

        // The clock was shifted by the pause duration...
        assertThat(step("pz-2", "wait").getStartedAt()).isAfter(originalStartedAt);

        // ...and the timer completes after the shifted due moment.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("pz-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
            assertThat(process("pz-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });
        assertThat(worker.invocationsOf("after")).isEqualTo(1);
    }

    /** E2E-PAUSE-03 — a message delivered during the pause completes the wait, the successor is held. */
    @Test
    void messageDeliveredDuringPauseCompletesTheStepButHoldsTheSuccessor() {
        worker.on("after", TestWorker.succeed());

        createProcess("message", "pz-3");
        assertThat(step("pz-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        pause("pz-3");

        sendMessage("payment-received", "pz-3", new Variable("paymentId", "P-42"));

        // The message is accepted: the step completes and its payload merges into the process...
        assertThat(step("pz-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("pz-3").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("paymentId", "P-42"));
        // ...but the successor is held and the process stays PAUSED.
        assertThat(worker.invocationsOf("after")).isZero();
        assertThat(process("pz-3").getStatus()).isEqualTo(ProcessStatus.PAUSED);

        resume("pz-3");
        assertThat(process("pz-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("after")).isEqualTo(1);
    }

    /** E2E-PAUSE-04 — definition pause: running instances pause, new instances are born paused, resume finishes both. */
    @Test
    void pausedDefinitionPausesRunningInstancesAndNewInstancesAreBornPaused() {
        worker.on("s1", TestWorker.deferForever());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "pz-4a");
        assertThat(step("pz-4a", "s1").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        pauseWorkflowUseCase.handle("sequential-3");
        assertThat(workflowDefinitionRepository.findById("sequential-3").orElseThrow().paused()).isTrue();
        assertThat(process("pz-4a").getStatus()).isEqualTo(ProcessStatus.PAUSED);

        // New instances are deliberately still accepted — but born PAUSED, nothing runs.
        createProcess("sequential-3", "pz-4b");
        assertThat(process("pz-4b").getStatus()).isEqualTo(ProcessStatus.PAUSED);
        assertThat(process("pz-4b").getPausedAt()).isNotNull();
        assertThat(steps("pz-4b"))
                .allMatch(se -> StepExecutionStatus.CREATED.equals(se.getStatus()));
        assertThat(worker.invocationsOf("s1")).isEqualTo(1); // only instance A's dispatch

        // Instance A's in-flight task completes during the pause; the flow stays held.
        worker.on("s1", TestWorker.succeed());
        completeDeferredStep("pz-4a", "s1");
        assertThat(process("pz-4a").getStatus()).isEqualTo(ProcessStatus.PAUSED);
        assertThat(worker.invocationsOf("s2")).isZero();

        resumeWorkflowUseCase.handle("sequential-3");
        assertThat(workflowDefinitionRepository.findById("sequential-3").orElseThrow().paused()).isFalse();

        // Both instances finish: A from where it was held, B from its (born-paused) start.
        assertThat(process("pz-4a").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("pz-4b").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
