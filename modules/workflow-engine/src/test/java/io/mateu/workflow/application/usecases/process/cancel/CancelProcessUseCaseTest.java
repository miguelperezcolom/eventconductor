package io.mateu.workflow.application.usecases.process.cancel;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.childcancel.CancelChildProcessService;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.support.TrackedProcessLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelProcessUseCaseTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock DownstreamEventPublisher downstreamEventPublisher;
    @Mock WorkflowMetrics workflowMetrics;
    @Mock NotifyParentStepService notifyParentStepService;
    @Mock io.mateu.workflow.application.services.RecordProcessTraceService recordProcessTraceService;
    @Mock CancelChildProcessService cancelChildProcessService;
    @Mock ProcessLockService processLockService;
    @Mock org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase> stepOverProcessUseCase;
    @Mock io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase stepOver;

    @InjectMocks CancelProcessUseCase useCase;

    final TrackedProcessLock lock = new TrackedProcessLock();
    final java.util.concurrent.atomic.AtomicBoolean lockHeld = new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    void lockIsGranted() {
        lenient().when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(lock.granted());
        lenient().when(stepOverProcessUseCase.getObject()).thenReturn(stepOver);
    }

    @Test
    void releasesTheLocksTheCancelledProcessHoldsUnderItsLock() {
        // A cancelled holder must not wedge its key until the lease reaper comes round.
        var process = process("p-1");
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of());
        doAnswer(invocation -> {
            lock.assertHeld("the cancelled process's locks are released");
            return null;
        }).when(stepOver).releaseLocksOf("p-1");

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(stepOver).releaseLocksOf("p-1");
    }

    @Test
    void anAlreadyFinishedProcessReleasesNothingAgain() {
        when(processRepository.findById("p-1")).thenReturn(Optional.of(
                Process.builder().id("p-1").status(ProcessStatus.COMPLETED).build()));

        useCase.handle(new CancelProcessCommand("p-1"));

        verifyNoInteractions(stepOver);
    }

    private Process process(String id) {
        return Process.builder().id(id).status(ProcessStatus.RUNNING).build();
    }

    private StepExecution se(String id, StepExecutionStatus status) {
        return StepExecution.builder().id(id).processId("p-1").status(status).build();
    }

    @Test
    void cancelsCREATEDStepExecutions() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.CREATED);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        ArgumentCaptor<StepExecution> seCaptor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(seCaptor.capture());
        assertThat(seCaptor.getValue().getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        ArgumentCaptor<Process> pCaptor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(pCaptor.capture());
        assertThat(pCaptor.getValue().getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        verify(workflowMetrics).processCancelled(any(), any());
    }

    @Test
    void everyCancelledStepIsOfferedToTheChildCancelCascade() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.PENDING);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        // The hook receives the step already CANCELLED so it can cascade into a child
        // process if the step is a PROCESS step.
        verify(cancelChildProcessService).stepReachedTerminalStatus(
                argThat(step -> step.getStatus() == StepExecutionStatus.CANCELLED && "se-1".equals(step.id())));
    }

    @Test
    void untouchedTerminalStepsAreNotOfferedToTheChildCancelCascade() {
        var process = process("p-1");
        var completed = se("se-1", StepExecutionStatus.COMPLETED);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(cancelChildProcessService, never()).stepReachedTerminalStatus(any());
    }

    @Test
    void publishesCancellationEventForPENDINGStep() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.PENDING);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(downstreamEventPublisher).publish(any(TaskCancellationRequested.class), any());
    }

    @Test
    void publishesCancellationEventForRUNNINGStep() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.RUNNING);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(downstreamEventPublisher).publish(any(TaskCancellationRequested.class), any());
    }

    @Test
    void doesNotCancelCompletedStepExecutions() {
        var process = process("p-1");
        var completed = se("se-1", StepExecutionStatus.COMPLETED);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed));

        useCase.handle(new CancelProcessCommand("p-1"));

        // Completed steps stay untouched, but the process itself is marked CANCELLED
        // (first thing, so the orchestration loop can't dispatch steps mid-cancellation).
        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository).save(argThat(p -> p.getStatus() == ProcessStatus.CANCELLED));
    }

    @Test
    void doesNotCancelErrorStepExecutions() {
        var process = process("p-1");
        var error = se("se-1", StepExecutionStatus.ERROR);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(error));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository).save(argThat(p -> p.getStatus() == ProcessStatus.CANCELLED));
    }

    @Test
    void aPausedProcessCanStillBeCancelled() {
        var process = process("p-1").withStatus(ProcessStatus.PAUSED);
        var se = se("se-1", StepExecutionStatus.PENDING);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(processRepository).save(argThat(p -> p.getStatus() == ProcessStatus.CANCELLED));
        verify(stepExecutionRepository).save(argThat(s -> s.getStatus() == StepExecutionStatus.CANCELLED));
        verify(workflowMetrics).processCancelled(any(), any());
    }

    @Test
    void cancellingACompletedProcessIsANoOp() {
        var process = process("p-1").withStatus(ProcessStatus.COMPLETED);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
        verify(workflowMetrics, never()).processCancelled(any(), any());
    }

    @Test
    void cancelsUnderTheProcessLock() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.PENDING);
        when(processRepository.findById("p-1")).thenAnswer(invocation -> {
            lock.assertHeld("the process is read");
            return Optional.of(process);
        });
        when(stepExecutionRepository.findByProcess(process)).thenAnswer(invocation -> {
            lock.assertHeld("the steps are read");
            return List.of(se);
        });
        when(processRepository.save(any())).thenAnswer(invocation -> {
            lock.assertHeld("the process is written");
            return "p-1";
        });
        when(stepExecutionRepository.save(any())).thenAnswer(invocation -> {
            lock.assertHeld("a step is written");
            return "se-1";
        });

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(processRepository).save(argThat(p -> p.getStatus() == ProcessStatus.CANCELLED));
        verify(stepExecutionRepository).save(argThat(s -> s.getStatus() == StepExecutionStatus.CANCELLED));
    }

    @Test
    void theCascadeToOtherProcessesRunsAfterThisOnesLockIsReleased() {
        // Children (cancelled through this use case) and the parent (through its step update)
        // take their own locks; holding this one meanwhile would let a parent cancel and a child
        // reporting to its parent each wait on the other.
        var process = process("p-1");
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se("se-1", StepExecutionStatus.PENDING)));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(invocation -> {
            lockHeld.set(true);
            try {
                invocation.getArgument(1, Runnable.class).run();
            } finally {
                lockHeld.set(false);
            }
            return true;
        });
        doAnswer(invocation -> {
            assertThat(lockHeld).as("child cascade outside the lock").isFalse();
            return null;
        }).when(cancelChildProcessService).stepReachedTerminalStatus(any());
        doAnswer(invocation -> {
            assertThat(lockHeld).as("parent notification outside the lock").isFalse();
            return null;
        }).when(notifyParentStepService).processReachedTerminalStatus(any());

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(cancelChildProcessService).stepReachedTerminalStatus(any());
        verify(notifyParentStepService).processReachedTerminalStatus(any());
    }

    @Test
    void aCancelThatCannotTakeTheLockFailsInsteadOfDoingNothingSilently() {
        reset(processLockService);
        TrackedProcessLock.refused(processLockService);

        assertThatThrownBy(() -> useCase.handle(new CancelProcessCommand("p-1")))
                .isInstanceOf(ConcurrentProcessAccessException.class);

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
        verifyNoInteractions(cancelChildProcessService, notifyParentStepService, recordProcessTraceService);
    }
}
