package io.mateu.workflow.application.usecases.checktimeout.checksteptimeout;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckStepTimeoutHandlerTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock ProcessLockService processLockService;
    @Mock WorkflowMetrics workflowMetrics;

    @InjectMocks CheckStepTimeoutHandler handler;

    private StepExecution pendingSeWithTimeout(long timeoutMillis, LocalDateTime startedAt) {
        Step step = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, timeoutMillis, 0, false, null, 0, null);
        return StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(startedAt)
                .build();
    }

    @Test
    void setsTimeoutStatusAndLogsWhenTimedOut() {
        var se = pendingSeWithTimeout(100, LocalDateTime.now().minusSeconds(60));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CheckStepTimeoutCommand("se-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        verify(logMessageRepository).save(any());
        verify(workflowMetrics).stepExecutionFinished(any(), eq(StepExecutionStatus.TIMEOUT), any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenLockNotAcquired() {
        var se = pendingSeWithTimeout(100, LocalDateTime.now().minusSeconds(60));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(false);

        handler.handle(new CheckStepTimeoutCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void skipsWhenStepNotPendingOrRunning() {
        var se = pendingSeWithTimeout(100, LocalDateTime.now().minusSeconds(60))
                .withStatus(StepExecutionStatus.COMPLETED);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CheckStepTimeoutCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenNotYetTimedOut() {
        var se = pendingSeWithTimeout(Long.MAX_VALUE, LocalDateTime.now());
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CheckStepTimeoutCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenTimeoutIsZero() {
        var se = pendingSeWithTimeout(0, LocalDateTime.now().minusSeconds(60));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CheckStepTimeoutCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }
}
