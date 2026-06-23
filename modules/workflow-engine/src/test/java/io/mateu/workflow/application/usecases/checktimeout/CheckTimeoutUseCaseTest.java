package io.mateu.workflow.application.usecases.checktimeout;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.checktimeout.checksteptimeout.CheckStepTimeoutHandler;
import io.mateu.workflow.domain.aggregates.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckTimeoutUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock CheckStepTimeoutHandler checkStepTimeoutHandler;

    @InjectMocks CheckTimeoutUseCase useCase;

    private Step stepWithTimeout(long millis) {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, false, "t", null, null, millis, 0, false, null);
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
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(timedOut));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler).handle(any());
    }

    @Test
    void doesNotTriggerCheckForStepNotYetTimedOut() {
        var notTimedOut = pendingSeWithTimeout(Long.MAX_VALUE, LocalDateTime.now());
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(notTimedOut));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCheckForStepWithNoTimeout() {
        var noTimeout = pendingSeWithTimeout(0, LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(noTimeout));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler, never()).handle(any());
    }

    @Test
    void doesNotTriggerCheckForStepFromDifferentProcess() {
        var timedOut = pendingSeWithTimeout(100, LocalDateTime.now().minusSeconds(10));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(timedOut));

        useCase.handle(new CheckTimeoutCommand("other-process"));

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
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));

        useCase.handle(new CheckTimeoutCommand("p-1"));

        verify(checkStepTimeoutHandler, never()).handle(any());
    }
}
