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
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.dtos.MessageType;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    void whatTheCallerSaidAboutTheFailureIsWhatTheProcessRecords() {
        // The command has carried a log line since forever and this dropped it, so a process that
        // failed recorded "Task status changed to ERROR" and nothing about why. In embedded mode
        // that line is the exception the engine caught on the worker's behalf, and without it the
        // only copy of the reason was the application's stdout.
        var se = stepExecution("se-1", "p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("p-1")));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        useCase.handle(new UpdateStepExecutionCommand("se-1", List.of(),
                "Worker threw ResourceAccessException: I/O error on POST (caused by ConnectException)",
                StepExecutionStatus.ERROR));

        var saved = ArgumentCaptor.forClass(LogMessage.class);
        verify(logMessageRepository).save(saved.capture());
        assertThat(saved.getValue().getMessage()).contains("ConnectException");
        // Typed by outcome, so it lands in the Errors tab and on the graph's hover card.
        assertThat(MessageType.isError(saved.getValue().getMessageType())).isTrue();
        assertThat(saved.getValue().getStepExecutionId()).isEqualTo("se-1");
    }

    @Test
    void aFailureWithNothingSaidAboutItStillGetsAnErrorLine() {
        var se = stepExecution("se-1", "p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("p-1")));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        useCase.handle(new UpdateStepExecutionCommand("se-1", List.of(), "  ", StepExecutionStatus.TIMEOUT));

        var saved = ArgumentCaptor.forClass(LogMessage.class);
        verify(logMessageRepository).save(saved.capture());
        assertThat(MessageType.isError(saved.getValue().getMessageType())).isTrue();
        assertThat(saved.getValue().getMessage()).contains("TIMEOUT");
    }

    @Test
    void aSuccessfulStepIsNotFiledAsAnError() {
        var se = stepExecution("se-1", "p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("p-1")));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        useCase.handle(new UpdateStepExecutionCommand("se-1", List.of(), "all good",
                StepExecutionStatus.COMPLETED));

        var saved = ArgumentCaptor.forClass(LogMessage.class);
        verify(logMessageRepository).save(saved.capture());
        assertThat(MessageType.isError(saved.getValue().getMessageType())).isFalse();
        assertThat(saved.getValue().getMessage()).isEqualTo("all good");
    }

    @Test
    void lockNotAcquiredThrowsRetryableRatherThanDroppingTheUpdate() {
        // A lock the queue never granted must not silently discard the worker's result — that would
        // leave the step PENDING/RUNNING forever. It throws a retryable failure so the event is
        // redelivered, and nothing is written in the meantime.
        var se = stepExecution("se-1", "p-1");
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.runExclusively(eq("p-1"), any())).thenReturn(false);

        var command = new UpdateStepExecutionCommand("se-1", List.of(), "", StepExecutionStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.handle(command))
                .isInstanceOf(io.mateu.workflow.application.out.ConcurrentProcessAccessException.class);

        verify(repository, never()).save(any());
        verify(processRepository, never()).save(any());
    }

    @Test
    void aStatusUpdateForAStepThatDoesNotExistIsPoison() {
        // A report for a step execution the engine has never heard of (a stale or forged event)
        // cannot succeed on any retry — it throws a typed poison exception so the delivery machinery
        // parks it rather than retrying it forever.
        when(repository.findById("no-such-step")).thenReturn(Optional.empty());

        var command = new UpdateStepExecutionCommand("no-such-step", List.of(), "", StepExecutionStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.handle(command))
                .isInstanceOf(io.mateu.workflow.application.out.UnknownStepExecutionException.class);

        verify(processLockService, never()).runExclusively(any(), any());
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
