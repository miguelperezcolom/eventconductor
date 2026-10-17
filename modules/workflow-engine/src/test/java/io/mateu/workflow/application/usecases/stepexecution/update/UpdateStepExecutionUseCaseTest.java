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
    @Mock io.mateu.workflow.application.services.AutoRetryService autoRetryService;


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

    // --- A failure with retries left is parked in the same locked update that records it ---

    private UpdateStepExecutionUseCase withRealRetries() {
        return new UpdateStepExecutionUseCase(repository, logMessageRepository, processRepository, processLockService,
                messageSubscriptionService, workflowMetrics, new io.mateu.workflow.application.services.AutoRetryService(
                        new io.mateu.workflow.application.services.BackoffPolicy(1000, 2.0, 60000, 0), workflowMetrics));
    }

    private StepExecution runningWithRetries(int attemptCount, int retries) {
        Step step = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null,
                null, null, 0, null, null, null, null, 0, retries, false, null, 0, null);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .attemptCount(attemptCount)
                .status(StepExecutionStatus.RUNNING).build();
    }

    @Test
    void anErrorWithRetriesLeftIsNeverPersistedAsAFinalError() {
        // Before, the ERROR was committed here and parked later by the status-change handler; a
        // sibling branch completing in between read it as final, errored the process and started
        // compensating while the retry was still coming.
        var se = runningWithRetries(0, 2);
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("p-1")));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());
        var saved = new java.util.ArrayList<StepExecutionStatus>();
        when(repository.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0, StepExecution.class).getStatus());
            return "se-1";
        });

        withRealRetries().handle(new UpdateStepExecutionCommand("se-1", List.of(), "boom", StepExecutionStatus.ERROR));

        assertThat(saved).containsExactly(StepExecutionStatus.AWAITING_RETRY);
        assertThat(se.getAttemptCount()).isEqualTo(1);
        verify(workflowMetrics).retryPerformed("wd-1", WorkflowMetrics.RetryTrigger.AUTO);
        // Still metered and logged as the failure it was.
        verify(workflowMetrics).stepExecutionFinished(any(), eq(StepExecutionStatus.ERROR), any());
    }

    @Test
    void anErrorWithNoRetriesLeftIsFinal() {
        var se = runningWithRetries(2, 2);
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("p-1")));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        withRealRetries().handle(new UpdateStepExecutionCommand("se-1", List.of(), "boom", StepExecutionStatus.ERROR));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        verify(workflowMetrics, never()).retryPerformed(any(), any());
    }

    @Test
    void aNonRetryableErrorIsFinalEvenWithRetriesLeft() {
        var se = runningWithRetries(0, 2);
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("p-1")));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        withRealRetries().handle(new UpdateStepExecutionCommand("se-1", List.of(), "bad request",
                StepExecutionStatus.ERROR, true));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        verify(workflowMetrics, never()).retryPerformed(any(), any());
    }

    @Test
    void aReportForAStepAwaitingItsRetryIsIgnored() {
        // No attempt is running while the step waits out its backoff: a report now is the failure
        // that parked it, redelivered, and applying it would burn a second attempt.
        var se = runningWithRetries(0, 3);
        se.scheduleRetry(java.time.Duration.ofSeconds(1));
        when(repository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.runExclusively(eq("p-1"), any())).thenAnswer(RunsTheAction.granted());

        withRealRetries().handle(new UpdateStepExecutionCommand("se-1", List.of(), "boom", StepExecutionStatus.ERROR));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.AWAITING_RETRY);
        assertThat(se.getAttemptCount()).isEqualTo(1);
        verify(repository, never()).save(any());
    }
}
