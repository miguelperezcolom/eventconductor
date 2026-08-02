package io.mateu.workflow.application.usecases.correlatemessage.completemessagestep;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Process;
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
class CompleteMessageStepHandlerTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock ProcessLockService processLockService;
    @Mock WorkflowMetrics workflowMetrics;

    @Mock MessageSubscriptionService messageSubscriptionService;


    @InjectMocks CompleteMessageStepHandler handler;

    private StepExecution pendingMessageSe(String messageName) {
        Step step = new Step("s1", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait for message", null, null, null, null, false, null, null, null, null, null, 0, null, messageName, null, null, 0, 0, false, null, 0, null);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now().minusSeconds(60))
                .variables(List.of())
                .build();
    }

    private Process process(String businessKey) {
        return Process.builder().id("p-1").businessKey(businessKey).variables(List.of()).build();
    }

    @Test
    void completesMergesMessageVariablesAndLogs() {
        var se = pendingMessageSe("payment-received");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("bk-1")));

        handler.handle(new CompleteMessageStepCommand("se-1", "payment-received", "bk-1",
                List.of(new Variable("paymentId", "P-9"))));

        ArgumentCaptor<Process> processCaptor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(processCaptor.capture());
        assertThat(processCaptor.getValue().getVariables())
                .contains(new Variable("paymentId", "P-9"));

        ArgumentCaptor<StepExecution> seCaptor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(seCaptor.capture());
        assertThat(seCaptor.getValue().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        verify(logMessageRepository).save(any());
        verify(workflowMetrics).stepExecutionFinished(any(), eq(StepExecutionStatus.COMPLETED), any());
        // The message payload became process state; a sibling WAIT_FOR_MESSAGE correlating on
        // one of those variables only sees it if the subscriptions are rearmed here.
        verify(messageSubscriptionService).rearm(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenLockNotAcquired() {
        var se = pendingMessageSe("payment-received");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(false);

        handler.handle(new CompleteMessageStepCommand("se-1", "payment-received", "bk-1", List.of()));

        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void skipsDuplicateDeliveryOnceStepIsCompleted() {
        var se = pendingMessageSe("payment-received").withStatus(StepExecutionStatus.COMPLETED);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteMessageStepCommand("se-1", "payment-received", "bk-1", List.of()));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenStepIsNotAMessageCatch() {
        Step action = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(action))
                .status(StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now().minusSeconds(60))
                .build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteMessageStepCommand("se-1", "payment-received", "bk-1", List.of()));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenMessageNameDiffers() {
        var se = pendingMessageSe("payment-received");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);

        handler.handle(new CompleteMessageStepCommand("se-1", "something-else", "bk-1", List.of()));

        verify(stepExecutionRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }

    @Test
    void skipsWhenCorrelationNoLongerMatchesInsideTheLock() {
        var se = pendingMessageSe("payment-received");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processLockService.tryLock("p-1")).thenReturn(true);
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("other-key")));

        handler.handle(new CompleteMessageStepCommand("se-1", "payment-received", "bk-1", List.of()));

        verify(stepExecutionRepository, never()).save(any());
        verify(processRepository, never()).save(any());
        verify(processLockService).unlock("p-1");
    }
}
