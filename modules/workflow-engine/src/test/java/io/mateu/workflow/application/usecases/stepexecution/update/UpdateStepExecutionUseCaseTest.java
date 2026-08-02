package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.support.RunsTheAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateStepExecutionUseCaseTest {

    @Mock StepExecutionRepository repository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock ProcessRepository processRepository;
    @Mock ProcessLockService processLockService;
    @Mock WorkflowMetrics workflowMetrics;

    @Mock MessageSubscriptionService messageSubscriptionService;


    @InjectMocks UpdateStepExecutionUseCase useCase;

    private StepExecution stepExecution(String id, String processId) {
        return StepExecution.builder()
                .id(id).processId(processId)
                .status(StepExecutionStatus.PENDING).build();
    }

    private Process process(String id) {
        return Process.builder().id(id).variables(List.of()).status(ProcessStatus.RUNNING).build();
    }

    @Test
    void updatesStatusWhenLockAcquired() {
        var se = stepExecution("se-1", "p-1");
        var proc = process("p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(proc));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        useCase.handle(new UpdateStepExecutionCommand("se-1", List.of(), "", StepExecutionStatus.COMPLETED));

        verify(repository, times(2)).findById("se-1");
        verify(repository).save(any(StepExecution.class));
        verify(processRepository).save(any(Process.class));
        verify(logMessageRepository).save(any());
        verify(workflowMetrics).stepExecutionFinished(any(), eq(StepExecutionStatus.COMPLETED), any());
        // The variables just written may be what a sibling WAIT_FOR_MESSAGE correlates on;
        // its stored key only follows them if this call is here.
        verify(messageSubscriptionService).rearm(proc);
        verify(processLockService).runExclusively(eq("p-1"), any());
    }

    @Test
    void doesNotUpdateWhenLockNotAcquired() {
        var se = stepExecution("se-1", "p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.runExclusively(eq("p-1"), any())).thenReturn(false);

        useCase.handle(new UpdateStepExecutionCommand("se-1", List.of(), "", StepExecutionStatus.COMPLETED));

        verify(repository, never()).save(any());
        verify(processRepository, never()).save(any());
    }

    @Test
    void updatesProcessVariablesFromCommand() {
        var se = stepExecution("se-1", "p-1");
        var proc = process("p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(proc));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        var newVars = List.of(new Variable("k", "v"));
        useCase.handle(new UpdateStepExecutionCommand("se-1", newVars, "", StepExecutionStatus.RUNNING));

        verify(processRepository).save(any(Process.class));
        // RUNNING is not a final outcome — no step-execution metric yet.
        verify(workflowMetrics, never()).stepExecutionFinished(any(), any(), any());
    }
}
