package io.mateu.workflow.application.usecases.process.cancel;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelProcessUseCaseTest {

    @Mock ProcessRepository processRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock DownstreamEventPublisher downstreamEventPublisher;

    @InjectMocks CancelProcessUseCase useCase;

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
    }

    @Test
    void publishesCancellationEventForPENDINGStep() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.PENDING);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(downstreamEventPublisher).publish(any(TaskCancellationRequested.class));
    }

    @Test
    void publishesCancellationEventForRUNNINGStep() {
        var process = process("p-1");
        var se = se("se-1", StepExecutionStatus.RUNNING);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(se));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(downstreamEventPublisher).publish(any(TaskCancellationRequested.class));
    }

    @Test
    void doesNotCancelCompletedStepExecutions() {
        var process = process("p-1");
        var completed = se("se-1", StepExecutionStatus.COMPLETED);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(completed));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
    }

    @Test
    void doesNotCancelErrorStepExecutions() {
        var process = process("p-1");
        var error = se("se-1", StepExecutionStatus.ERROR);

        when(processRepository.findById("p-1")).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(process)).thenReturn(List.of(error));

        useCase.handle(new CancelProcessCommand("p-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
    }
}
