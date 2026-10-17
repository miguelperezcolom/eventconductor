package io.mateu.workflow.application.usecases.process.update;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.parentnotify.NotifyParentStepService;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessUpdateStepExecutionUpdateUseCaseTest {

    @Mock ProcessRepository repository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock WorkflowMetrics workflowMetrics;
    @Mock NotifyParentStepService notifyParentStepService;
    @Mock io.mateu.workflow.application.services.RecordProcessTraceService recordProcessTraceService;

    @InjectMocks ProcessUpdateStepExecutionUpdateUseCase useCase;

    private Process process(String id, ProcessStatus status) {
        return Process.builder().id(id).status(status).build();
    }

    private StepExecution se(StepExecutionStatus status) {
        return StepExecution.builder().id("se-1").processId("p-1").status(status).build();
    }

    @Test
    void setsRunningWhenSomePending() {
        var proc = process("p-1", ProcessStatus.PENDING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.PENDING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    @Test
    void setsRunningWhenSomeRunning() {
        var proc = process("p-1", ProcessStatus.PENDING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.RUNNING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    @Test
    void setsCompletedWhenAllCompleted() {
        var proc = process("p-1", ProcessStatus.RUNNING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.COMPLETED)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(captor.getValue().getCompletionPercentage()).isEqualTo(100);
        verify(workflowMetrics).processCompleted(any(), any());
    }

    @Test
    void keepsCANCELLEDStatusWhenAlreadyCancelled() {
        var proc = process("p-1", ProcessStatus.CANCELLED);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.RUNNING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.CANCELLED);
    }

    @Test
    void keepsERRORStatusWhenAlreadyError() {
        var proc = process("p-1", ProcessStatus.ERROR);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.PENDING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.ERROR);
        // The process was already in ERROR — a sticky status is not a new transition.
        verify(workflowMetrics, never()).processErrored(any(), any());
    }

    @Test
    void setsStartedWhenTransitioningToRunning() {
        var proc = process("p-1", ProcessStatus.PENDING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.PENDING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStarted()).isNotNull();
    }

    @Test
    void keepsPAUSEDStatusWhenStepsCompleteDuringThePause() {
        var proc = process("p-1", ProcessStatus.PAUSED);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        // A worker report or a correlated message completed a step while paused.
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.COMPLETED)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        // Sticky: only ResumeProcessUseCase leaves PAUSED — even a 100% completed flow waits.
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.PAUSED);
        verify(workflowMetrics, never()).processCompleted(any(), any());
    }

    @Test
    void setsFinishedWhenCompleted() {
        var proc = process("p-1", ProcessStatus.RUNNING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId(proc.id())).thenReturn(List.of(se(StepExecutionStatus.COMPLETED)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFinished()).isNotNull();
    }

    private List<StepExecution> completedPlus(int completed, StepExecutionStatus... others) {
        var executions = new java.util.ArrayList<StepExecution>();
        for (int i = 0; i < completed; i++) {
            executions.add(StepExecution.builder().id("se-" + i).processId("p-1")
                    .status(StepExecutionStatus.COMPLETED).build());
        }
        for (int i = 0; i < others.length; i++) {
            executions.add(StepExecution.builder().id("se-other-" + i).processId("p-1")
                    .status(others[i]).build());
        }
        return executions;
    }

    @Test
    void aProcessWithAStepStillRunningIsNotCompletedEvenWhenTheRestRoundsUpToAHundred() {
        // 199 of 200: Math.round made that 100%, and 100% alone meant COMPLETED — with a step
        // still RUNNING, the parent notified and the completion counted.
        var proc = process("p-1", ProcessStatus.RUNNING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId("p-1"))
                .thenReturn(completedPlus(199, StepExecutionStatus.RUNNING));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(captor.getValue().getCompletionPercentage()).isEqualTo(99);
        assertThat(captor.getValue().getFinished()).isNull();
        verify(workflowMetrics, never()).processCompleted(any(), any());
        verify(notifyParentStepService, never()).processReachedTerminalStatus(any());
    }

    @Test
    void aProcessWithStepsNotYetStartedIsNotCompleted() {
        // START completed, the rest still CREATED waiting for the step-over to dispatch them.
        var proc = process("p-1", ProcessStatus.RUNNING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId("p-1"))
                .thenReturn(completedPlus(1, StepExecutionStatus.CREATED, StepExecutionStatus.CREATED));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(captor.getValue().getCompletionPercentage()).isEqualTo(33);
    }

    @Test
    void keepsCOMPLETEDStatusWhenALateEventArrives() {
        // An END transition completed the process and CANCELLED the branches it did not take, so
        // the steps alone no longer add up to 100%. A late or redelivered status event must not
        // reopen it as RUNNING.
        var proc = process("p-1", ProcessStatus.COMPLETED).withCompletionPercentage(100);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId("p-1"))
                .thenReturn(completedPlus(3, StepExecutionStatus.CANCELLED));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(captor.getValue().getCompletionPercentage()).isEqualTo(100);
        verify(workflowMetrics, never()).processCompleted(any(), any());
        verify(notifyParentStepService, never()).processReachedTerminalStatus(any());
    }

    @Test
    void aStepQueuedOnALockKeepsTheProcessRunning() {
        var proc = process("p-1", ProcessStatus.RUNNING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcessId("p-1"))
                .thenReturn(completedPlus(0, StepExecutionStatus.WAITING_ON_LOCK));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }
}
