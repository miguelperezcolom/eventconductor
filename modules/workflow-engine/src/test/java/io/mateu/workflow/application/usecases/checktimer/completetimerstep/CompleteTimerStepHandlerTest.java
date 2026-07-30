package io.mateu.workflow.application.usecases.checktimer.completetimerstep;

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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompleteTimerStepHandlerTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock ProcessLockService processLockService;
    @Mock WorkflowMetrics workflowMetrics;

    @InjectMocks CompleteTimerStepHandler handler;

    private StepExecution pendingTimerSe(long durationMillis, String untilVariable, LocalDateTime startedAt, Variable... variables) {
        Step step = new Step("s1", "wd-1", StepType.TIMER, "Wait", null, null, null, null, false, null, null, null, null, null, durationMillis, untilVariable, null, null, null, 0, 0, false, null, 0);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(startedAt)
                .variables(List.of(variables))
                .build();
    }

    @Test
    void completesAndLogsWhenTimerIsDue() {
        var se = pendingTimerSe(100, null, LocalDateTime.now().minusSeconds(60));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        verify(logMessageRepository).save(any());
        verify(workflowMetrics).stepExecutionFinished(any(), eq(StepExecutionStatus.COMPLETED), any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void completesWhenDateVariableTimerIsDue() {
        var se = pendingTimerSe(0, "resumeAt", LocalDateTime.now().minusSeconds(60),
                new Variable("resumeAt", LocalDateTime.now().minusSeconds(1).toString()));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        ArgumentCaptor<StepExecution> captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void skipsWhenLockNotAcquired() {
        var se = pendingTimerSe(100, null, LocalDateTime.now().minusSeconds(60));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(false);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void skipsWhenStepNotPending() {
        var se = pendingTimerSe(100, null, LocalDateTime.now().minusSeconds(60))
                .withStatus(StepExecutionStatus.COMPLETED);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenTimerNotYetDue() {
        var se = pendingTimerSe(Long.MAX_VALUE, null, LocalDateTime.now());
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenStepIsNotATimer() {
        Step action = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(action))
                .status(StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now().minusSeconds(60))
                .build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenTimerIsMisconfigured() {
        var se = pendingTimerSe(0, "resumeAt", LocalDateTime.now().minusSeconds(60));
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteTimerStepCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }
}
