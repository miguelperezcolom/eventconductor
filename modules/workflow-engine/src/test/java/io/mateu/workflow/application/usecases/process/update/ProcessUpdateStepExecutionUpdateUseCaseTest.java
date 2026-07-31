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
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.PENDING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    @Test
    void setsRunningWhenSomeRunning() {
        var proc = process("p-1", ProcessStatus.PENDING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.RUNNING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    @Test
    void setsCompletedWhenAllCompleted() {
        var proc = process("p-1", ProcessStatus.RUNNING);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.COMPLETED)));

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
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.RUNNING)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.CANCELLED);
    }

    @Test
    void keepsERRORStatusWhenAlreadyError() {
        var proc = process("p-1", ProcessStatus.ERROR);
        when(repository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.PENDING)));

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
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.PENDING)));

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
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.COMPLETED)));

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
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se(StepExecutionStatus.COMPLETED)));

        useCase.handle(new ProcessStepExecutionUpdateCommand("p-1"));

        ArgumentCaptor<Process> captor = ArgumentCaptor.forClass(Process.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFinished()).isNotNull();
    }
}
