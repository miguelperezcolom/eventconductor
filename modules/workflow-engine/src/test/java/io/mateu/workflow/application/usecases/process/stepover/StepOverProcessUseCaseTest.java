package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.services.WorkflowOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import io.mateu.workflow.support.RunsTheAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class StepOverProcessUseCaseTest {

    @Mock ProcessRepository            processRepository;
    @Mock StepExecutionRepository      stepExecutionRepository;
    @Mock ProcessLockService           lockService;
    @Mock WorkflowMetrics              workflowMetrics;
    @Mock NotifyParentStepService      notifyParentStepService;
    @Mock CancelChildProcessService    cancelChildProcessService;
    @Mock io.mateu.workflow.application.out.DownstreamEventPublisher downstreamEventPublisher;
    @Spy  WorkflowOrchestrationService workflowOrchestrationService = new WorkflowOrchestrationService();

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    @InjectMocks StepOverProcessUseCase useCase;

    @BeforeEach
    void allowLock() {
        when(lockService.runExclusively(any(), any())).thenAnswer(RunsTheAction.granted());
    }

    private Process process(String id) {
        return Process.builder().id(id).status(ProcessStatus.RUNNING)
                .variables(List.of()).build();
    }

    private StepExecution se(String id, String stepId, StepType type, StepExecutionStatus status, int order) {
        return se(id, stepId, type, status, order, null);
    }

    /**
     * Steps are anchored to a precondition here as they are in a real definition: since only an
     * entry point may run with nothing to wait for, a fixture without one is a step that never
     * starts — which several of these tests would have passed anyway, for the wrong reason.
     */
    private StepExecution se(String id, String stepId, StepType type, StepExecutionStatus status,
                             int order, String preconditionStepId) {
        Step step = new Step(stepId, "wd-1", type, stepId, null, preconditionStepId, null, null, false, "topic", "form-1", null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        return StepExecution.builder()
                .id(id).processId("p-1").workflowDefinitionId("wd-1")
                .stepId(stepId).stepJson(JsonSerializer.toJson(step))
                .status(status).order(order).variables(List.of()).build();
    }

    /** A START that has already been passed through, so its successors are eligible. */
    private StepExecution startedFlow() {
        return se("se-start", "start", StepType.START, StepExecutionStatus.COMPLETED, -1);
    }

    @Test
    void completedStepsAreSkipped() {
        var process = process("p-1");
        var completed = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.COMPLETED, 0);
        var pending = se("se-2", "step-2", StepType.ACTION, StepExecutionStatus.CREATED, 1, "step-1");

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed, pending));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void endStepCompletesProcess() {
        var process = process("p-1");
        var endStep = se("se-1", "end", StepType.END, StepExecutionStatus.CREATED, 0, "start");

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(startedFlow(), endStep));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(captor.getValue().getCompletionPercentage()).isEqualTo(100);
        verify(workflowMetrics).processCompleted(any(), any());
    }

    @Test
    void stepsCancelledByAnEndTransitionAreOfferedToTheChildCancelCascade() {
        // An END fires while a PROCESS step is still waiting on its child: the PROCESS step
        // is cancelled by the END transition and the hook must see it so the child process
        // can be cancelled too.
        var process = process("p-1");
        var start = se("se-0", "start", StepType.START, StepExecutionStatus.COMPLETED, 0);
        Step spawnStep = new Step("spawn", "wd-1", StepType.PROCESS, "spawn", null, "start", null, null, false, null, null, null, "wd-child", null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var spawn = StepExecution.builder()
                .id("se-spawn").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("spawn").stepJson(JsonSerializer.toJson(spawnStep))
                .status(StepExecutionStatus.PENDING).order(1).variables(List.of()).build();
        Step endStep = new Step("end", "wd-1", StepType.END, "end", null, "start", null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var end = StepExecution.builder()
                .id("se-end").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("end").stepJson(JsonSerializer.toJson(endStep))
                .status(StepExecutionStatus.CREATED).order(2).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(start, spawn, end));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(cancelChildProcessService).stepReachedTerminalStatus(
                argThat(saved -> "spawn".equals(saved.getStepId())
                        && saved.getStatus() == StepExecutionStatus.CANCELLED));
        verify(cancelChildProcessService).stepReachedTerminalStatus(
                argThat(saved -> "end".equals(saved.getStepId())
                        && saved.getStatus() == StepExecutionStatus.COMPLETED));
    }

    @Test
    void theWorkerOfAStepCancelledByAnEndTransitionIsToldToStop() {
        // A branch dispatched to a worker while another branch reaches END: the transition
        // cancels the row and the worker heard nothing, so it finished and reported a task done
        // on a process that was already over.
        var process = process("p-1");
        var start = se("se-0", "start", StepType.START, StepExecutionStatus.COMPLETED, 0);
        var running = se("se-run", "branch", StepType.ACTION, StepExecutionStatus.RUNNING, 1, "start");
        var waiting = se("se-wait", "waiting", StepType.ACTION, StepExecutionStatus.CREATED, 2, "branch");
        Step endStep = new Step("end", "wd-1", StepType.END, "end", null, "start", null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var end = StepExecution.builder()
                .id("se-end").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("end").stepJson(JsonSerializer.toJson(endStep))
                .status(StepExecutionStatus.CREATED).order(3).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process))
                .thenReturn(List.of(start, running, waiting, end));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(downstreamEventPublisher).publish(
                eq(new io.mateu.workflow.dtos.events.integration.TaskCancellationRequested("se-run")), any());
        // The one that never left the engine has no worker to tell.
        verify(downstreamEventPublisher, never()).publish(
                eq(new io.mateu.workflow.dtos.events.integration.TaskCancellationRequested("se-wait")), any());
    }

    @Test
    void pendingStepDoesNotBlockAnIndependentStep() {
        // Pure dataflow: an in-flight step only gates its own successors. A CREATED step
        // whose preconditions are met starts even while an unrelated step is PENDING.
        var process = process("p-1");
        var pending = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.PENDING, 0, "start");
        var created = se("se-2", "step-2", StepType.ACTION, StepExecutionStatus.CREATED, 1, "start");

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(startedFlow(), pending, created));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStepId()).isEqualTo("step-2");
        assertThat(captor.getValue().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void pendingStepBlocksItsOwnSuccessor() {
        var process = process("p-1");
        var pending = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.PENDING, 0);
        Step successorStep = new Step("step-2", "wd-1", StepType.ACTION, "step-2", null, "step-1", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var successor = StepExecution.builder()
                .id("se-2").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-2").stepJson(JsonSerializer.toJson(successorStep))
                .status(StepExecutionStatus.CREATED).order(1).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(pending, successor));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void emptyExecutableStepsWithNoRemainingCompletesProcess() {
        var process = process("p-1");
        var completed = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.COMPLETED, 0);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void alreadyCancelledProcessIsNotOverwritten() {
        var process = Process.builder().id("p-1").status(ProcessStatus.CANCELLED)
                .variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void errorStepBlocksFlowAndMarksProcessAsError() {
        var process = process("p-1");
        var failed = se("se-1", "step-1", StepType.ACTION, StepExecutionStatus.ERROR, 0, "start");
        var next = se("se-2", "step-2", StepType.ACTION, StepExecutionStatus.CREATED, 1, "step-1");

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(startedFlow(), failed, next));

        useCase.handle(new StepOverProcessCommand("p-1"));

        // Successors of a failed step must not run, and the process must be flagged
        // as failed — never falsely completed.
        verify(stepExecutionRepository, never()).save(any());
        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.ERROR);
        verify(workflowMetrics).processErrored(any(), any());
        verify(workflowMetrics, never()).processCompleted(any(), any());
    }


    @Test
    void parallelStepsAreAllStarted() {
        var process = process("p-1");
        Step step1 = new Step("s1", "wd-1", StepType.ACTION, "s1", null, "start", null, null, true, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        Step step2 = new Step("s2", "wd-1", StepType.ACTION, "s2", null, "start", null, null, true, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var se1 = StepExecution.builder().id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("s1").stepJson(JsonSerializer.toJson(step1))
                .status(StepExecutionStatus.CREATED).order(0).variables(List.of()).build();
        var se2 = StepExecution.builder().id("se-2").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("s2").stepJson(JsonSerializer.toJson(step2))
                .status(StepExecutionStatus.CREATED).order(1).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(startedFlow(), se1, se2));

        useCase.handle(new StepOverProcessCommand("p-1"));

        verify(stepExecutionRepository, times(2)).save(any());
    }

    @Test
    void stepWithUnmetPreconditionStepIdIsNotStarted() {
        var process = process("p-1");
        Step prerequisite = new Step("prereq", "wd-1", StepType.ACTION, "prereq", null, "start", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        Step dependent = new Step("dep", "wd-1", StepType.ACTION, "dep", null, "prereq", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var sePrereq = StepExecution.builder().id("se-prereq").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("prereq").stepJson(JsonSerializer.toJson(prerequisite))
                .status(StepExecutionStatus.CREATED).order(0).variables(List.of()).build();
        var seDependent = StepExecution.builder().id("se-dep").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("dep").stepJson(JsonSerializer.toJson(dependent))
                .status(StepExecutionStatus.CREATED).order(1).variables(List.of()).build();

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(startedFlow(), sePrereq, seDependent));

        useCase.handle(new StepOverProcessCommand("p-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStepId()).isEqualTo("prereq");
    }
}
