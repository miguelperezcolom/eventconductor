package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.services.BackoffPolicy;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepExecutionStatusUpdatedEventHandlerTest {

    @Mock ProcessUpdateStepExecutionUpdateUseCase processUpdateUseCase;
    @Mock StepOverProcessUseCase stepOverProcessUseCase;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock DownstreamEventPublisher downstreamEventPublisher;
    @Mock WorkflowMetrics workflowMetrics;
    @Mock CancelChildProcessService cancelChildProcessService;
    @Mock BackoffPolicy backoffPolicy;
    // Real service (spy): the reverse-order rollback decision is pure and cheap, so we exercise
    // it for real and only stub the repositories that feed it.
    @Spy CompensationService compensationService = new CompensationService();

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks StepExecutionStatusUpdatedEventHandler handler;

    private Process proc;

    @BeforeEach
    void setUp() {
        proc = Process.builder().id("p-1").workflowDefinitionId("wd-1")
                .variables(List.of()).status(ProcessStatus.RUNNING).build();
        // advanceCompensation loads the process on every terminal event; default it so the
        // paths that don't set up a specific rollback still run.
        lenient().when(processRepository.findById(any())).thenReturn(Optional.of(proc));
    }

    private Step step(int retries, boolean compensable, String compensationStepId) {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "topic",
                null, null, null, null, 0, null, null, null, null, 0, retries, compensable, compensationStepId, 0, null);
    }

    private StepExecution se(int attemptCount, int retries, boolean compensable, String compensationStepId) {
        Step step = step(retries, compensable, compensationStepId);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .attemptCount(attemptCount)
                .status(StepExecutionStatus.ERROR)
                .variables(List.of()).build();
    }

    // A step that COMPLETED successfully and declares a compensation ("comp-step") — the one a
    // rollback actually reverses. The failed step (`se(...)`, ERROR) triggers the rollback but,
    // having committed nothing, is never compensated itself, so the compensable work has to come
    // from a step that succeeded.
    private StepExecution completedCompensable() {
        var step = new Step("orig", "wd-1", StepType.ACTION, "Orig", null, null, null, null, false, "topic",
                null, null, null, null, 0, null, null, null, null, 0, 0, true, "comp-step", 0, null);
        return StepExecution.builder()
                .id("se-orig").processId("p-1").workflowDefinitionId("wd-1").stepId("orig")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.COMPLETED)
                .finishedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0))
                .variables(List.of()).build();
    }

    @Test
    void canHandleStepExecutionStatusChanged() {
        org.assertj.core.api.Assertions.assertThat(handler.eventClass()).isEqualTo(StepExecutionStatusChanged.class);
    }

    @Test
    void retriesWhenAttemptsRemaining() {
        var se = se(0, 2, false, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(backoffPolicy.nextDelay(1)).thenReturn(java.time.Duration.ofMillis(50));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.ERROR, List.of()));

        verify(stepExecutionRepository).save(se);
        // The step is parked for backoff, NOT re-dispatched here: an immediate step-over would
        // defeat the backoff and hot-loop a fast-failing worker. The scheduler wakes it later.
        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.AWAITING_RETRY);
        verify(stepOverProcessUseCase, never()).handle(any());
        verify(processUpdateUseCase, never()).handle(any(), any());
        verify(workflowMetrics).retryPerformed("wd-1", WorkflowMetrics.RetryTrigger.AUTO);
        // While retries remain the step is not finally failed — a still-running child of a
        // PROCESS step must NOT be cancelled (the retry re-attaches to it).
        verify(cancelChildProcessService, never()).stepReachedTerminalStatus(any());
    }

    @Test
    void updatesProcessWhenRetriesExhausted() {
        var se = se(3, 3, false, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.ERROR, List.of()));

        verify(processUpdateUseCase).handle(any(), any());
        verify(stepOverProcessUseCase).handle(any());
        // Retries exhausted → the failure is final; a PROCESS step's child gets cancelled here.
        verify(cancelChildProcessService).stepReachedTerminalStatus(se);
    }

    @Test
    void cancelsWorkerOnTimeout() {
        var se = se(3, 3, false, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.TIMEOUT, List.of()));

        verify(downstreamEventPublisher).publish(any(TaskCancellationRequested.class));
    }

    @Test
    void triggersCompensationWhenCompensable() {
        var se = se(1, 1, true, "comp-step");
        var compensationStep = step(0, false, null).withId("comp-step");
        var compensationSe = StepExecution.builder()
                .id("comp-se").processId("p-1").workflowDefinitionId("wd-1").stepId("comp-step")
                .stepJson(JsonSerializer.toJson(compensationStep))
                .status(StepExecutionStatus.CREATED)
                .variables(List.of()).build();

        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(stepExecutionRepository.findByProcess(proc))
                .thenReturn(List.of(completedCompensable(), se, compensationSe));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.ERROR, List.of()));

        // The completed step is compensated; the failed step (se-1) triggers the rollback but is not.
        verify(stepExecutionRepository).save(compensationSe);
        verify(workflowMetrics).compensationTriggered("wd-1");
    }

    @Test
    void marksProcessCompensatedWhenRollbackChainCompletes() {
        // A completed compensable step whose compensation has already COMPLETED: the chain is
        // done, so the completion event flips the process to COMPENSATED. (se-1 is the failure that
        // triggered the rollback; it is not itself compensated.)
        var failed = se(1, 1, true, "comp-step");
        var compensationStep = step(0, false, null).withId("comp-step");
        var compensationSe = StepExecution.builder()
                .id("comp-se").processId("p-1").workflowDefinitionId("wd-1").stepId("comp-step")
                .stepJson(JsonSerializer.toJson(compensationStep))
                .status(StepExecutionStatus.COMPLETED)
                .variables(List.of()).build();

        when(stepExecutionRepository.findById("comp-se")).thenReturn(Optional.of(compensationSe));
        when(stepExecutionRepository.findByProcess(proc))
                .thenReturn(List.of(completedCompensable(), failed, compensationSe));

        handler.handle(new StepExecutionStatusChanged("comp-se", TaskStatus.COMPLETED, List.of()));

        verify(processRepository).save(argThat(p -> p.getStatus() == ProcessStatus.COMPENSATED));
    }

    @Test
    void aCompensatedProcessIsFinishedAtAHundredPercent() {
        // The rollback ran to the end: the process is as finished as one that completed, and a
        // bar frozen wherever the failure happened says the opposite.
        var failed = se(1, 1, true, "comp-step");
        var compensationSe = compensationExecution(StepExecutionStatus.COMPLETED);

        when(stepExecutionRepository.findById("comp-se")).thenReturn(Optional.of(compensationSe));
        when(stepExecutionRepository.findByProcess(proc))
                .thenReturn(List.of(completedCompensable(), failed, compensationSe));

        handler.handle(new StepExecutionStatusChanged("comp-se", TaskStatus.COMPLETED, List.of()));

        verify(processRepository).save(argThat(p -> p.getCompletionPercentage() == 100
                && p.getFinished() != null));
    }

    @Test
    void theStepsTheRollbackNeverReachedAreCancelled() {
        // They were left CREATED on a process that is over, so a finished saga went on showing
        // steps that looked like they were waiting their turn.
        var failed = se(1, 1, true, "comp-step");
        var compensationSe = compensationExecution(StepExecutionStatus.COMPLETED);
        var neverRan = StepExecution.builder()
                .id("se-later").processId("p-1").workflowDefinitionId("wd-1").stepId("later")
                .stepJson(JsonSerializer.toJson(step(0, false, null).withId("later")))
                .status(StepExecutionStatus.CREATED)
                .variables(List.of()).build();

        when(stepExecutionRepository.findById("comp-se")).thenReturn(Optional.of(compensationSe));
        when(stepExecutionRepository.findByProcess(proc))
                .thenReturn(List.of(completedCompensable(), failed, compensationSe, neverRan));

        handler.handle(new StepExecutionStatusChanged("comp-se", TaskStatus.COMPLETED, List.of()));

        verify(stepExecutionRepository).save(argThat(saved -> "later".equals(saved.getStepId())
                && saved.getStatus() == StepExecutionStatus.CANCELLED));
        // The failed step keeps its ERROR: it is why the process rolled back, and the record of it.
        assertThat(failed.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
    }

    @Test
    void theWorkerOfASiblingStillRunningIsToldToStop() {
        // On a parallel flow the rollback ends with siblings still dispatched. Flipping their row
        // to CANCELLED reaches nobody: the worker goes on and books a reservation for a saga that
        // has just been undone. CancelProcessUseCase has always sent this event; this path did not.
        var failed = se(1, 1, true, "comp-step");
        var compensationSe = compensationExecution(StepExecutionStatus.COMPLETED);
        var running = StepExecution.builder()
                .id("se-sibling").processId("p-1").workflowDefinitionId("wd-1").stepId("sibling")
                .stepJson(JsonSerializer.toJson(step(0, false, null).withId("sibling")))
                .status(StepExecutionStatus.RUNNING)
                .variables(List.of()).build();
        var neverDispatched = StepExecution.builder()
                .id("se-later").processId("p-1").workflowDefinitionId("wd-1").stepId("later")
                .stepJson(JsonSerializer.toJson(step(0, false, null).withId("later")))
                .status(StepExecutionStatus.CREATED)
                .variables(List.of()).build();

        when(stepExecutionRepository.findById("comp-se")).thenReturn(Optional.of(compensationSe));
        when(stepExecutionRepository.findByProcess(proc))
                .thenReturn(List.of(completedCompensable(), failed, compensationSe, running, neverDispatched));

        handler.handle(new StepExecutionStatusChanged("comp-se", TaskStatus.COMPLETED, List.of()));

        verify(downstreamEventPublisher).publish(new TaskCancellationRequested("se-sibling"));
        // The one that was never dispatched has no worker to tell — only the row is cancelled.
        verify(downstreamEventPublisher, never()).publish(new TaskCancellationRequested("se-later"));
        assertThat(running.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(neverDispatched.getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void marksProcessCompensationFailedWhenACompensationItselfFails() {
        // A compensable step failed and its compensation was run — but the compensation itself
        // errored (retries=0). The rollback cannot complete: the process must reach the distinct,
        // sticky COMPENSATION_FAILED terminal (never left in ERROR) and the failure must be metered.
        var failed = se(1, 1, true, "comp-step");
        var compensationSe = compensationExecution(StepExecutionStatus.ERROR);

        when(stepExecutionRepository.findById("comp-se")).thenReturn(Optional.of(compensationSe));
        when(stepExecutionRepository.findByProcess(proc))
                .thenReturn(List.of(completedCompensable(), failed, compensationSe));

        handler.handle(new StepExecutionStatusChanged("comp-se", TaskStatus.ERROR, List.of()));

        verify(processRepository).save(argThat(p -> p.getStatus() == ProcessStatus.COMPENSATION_FAILED
                && p.getFinished() != null));
        verify(workflowMetrics).compensationFailed("wd-1");
    }

    private StepExecution compensationExecution(StepExecutionStatus status) {
        return StepExecution.builder()
                .id("comp-se").processId("p-1").workflowDefinitionId("wd-1").stepId("comp-step")
                .stepJson(JsonSerializer.toJson(step(0, false, null).withId("comp-step")))
                .status(status)
                .variables(List.of()).build();
    }

    @Test
    void updatesProcessWhenCompleted() {
        Step step = step(0, false, null);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .attemptCount(0)
                .status(StepExecutionStatus.COMPLETED)
                .variables(List.of()).build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.COMPLETED, List.of()));

        verify(processUpdateUseCase).handle(any(), any());
        verify(stepOverProcessUseCase).handle(any());
    }
}
