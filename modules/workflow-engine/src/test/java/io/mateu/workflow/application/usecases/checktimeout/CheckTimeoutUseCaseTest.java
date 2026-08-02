package io.mateu.workflow.application.usecases.checktimeout;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.checktimeout.checksteptimeout.CheckStepTimeoutHandler;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckTimeoutUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock CheckStepTimeoutHandler checkStepTimeoutHandler;

    @InjectMocks CheckTimeoutUseCase useCase;

    private Step stepWithTimeout(long millis) {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, millis, 0, false, null, 0, null);
    }

    private StepExecution pendingSeWithTimeout(long timeoutMillis, LocalDateTime startedAt) {
        Step step = stepWithTimeout(timeoutMillis);
        return StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(startedAt)
                .build();
    }

    @Test
    void triggersCheckForTimedOutStepExecution() {
        var timedOut = pendingSeWithTimeout(100, LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(timedOut));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler).handle(any());
    }

    @Test
    void doesNotTriggerCheckForStepNotYetTimedOut() {
        var notTimedOut = pendingSeWithTimeout(Long.MAX_VALUE, LocalDateTime.now());
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(notTimedOut));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCheckForStepWithNoTimeout() {
        var noTimeout = pendingSeWithTimeout(0, LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(noTimeout));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void looksUpOnlyTheStepsOfTheCommandedProcess() {
        // The scoping is the query, not an in-memory filter: loading every live step in the
        // system and filtering by process turns one scheduler tick into a full table load per
        // process it finds.
        useCase.handle(new CheckTimeoutCommand("other-process"));

        verify(stepExecutionRepository).findPendingOrRunningByProcessId("other-process");
        verify(stepExecutionRepository, never()).findPendingOrRunning();
        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCheckForStepWithNullStartedAt() {
        Step step = stepWithTimeout(100);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(null)
                .build();
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(se));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCheckForExpiredStepOfPausedProcess() {
        var timedOut = pendingSeWithTimeout(100, LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(timedOut));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(
                Process.builder().id("p-1").status(ProcessStatus.PAUSED).build()));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        // The paused process freezes the clock — the expired deadline must not fire.
        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void onlyLooksUpTheProcessWhenAStepWouldFire() {
        var notTimedOut = pendingSeWithTimeout(Long.MAX_VALUE, LocalDateTime.now());
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(notTimedOut));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verifyNoInteractions(processRepository);
    }
}
