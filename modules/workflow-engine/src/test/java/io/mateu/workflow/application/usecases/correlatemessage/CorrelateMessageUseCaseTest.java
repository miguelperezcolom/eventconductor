package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepCommand;
import io.mateu.workflow.application.usecases.correlatemessage.completemessagestep.CompleteMessageStepHandler;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.*;
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
class CorrelateMessageUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock CompleteMessageStepHandler completeMessageStepHandler;

    @InjectMocks CorrelateMessageUseCase useCase;

    private Step messageStep(String messageName, String correlationExpression) {
        return new Step("s1", "wd-1", StepType.MESSAGE, "Wait for message", null, null, null, false, null, null, null, 0, null, messageName, correlationExpression, 0, 0, false, null);
    }

    private StepExecution pendingSe(Step step) {
        return StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .variables(List.of())
                .build();
    }

    private Process process(String businessKey, Variable... variables) {
        return Process.builder().id("p-1").businessKey(businessKey).variables(List.of(variables)).build();
    }

    @Test
    void correlatesByBusinessKeyByDefault() {
        var se = pendingSe(messageStep("payment-received", null));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("bk-1")));

        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(completeMessageStepHandler).handle(
                new CompleteMessageStepCommand("se-1", "payment-received", "bk-1", List.of()));
    }

    @Test
    void correlatesByExpressionOverProcessVariables() {
        var se = pendingSe(messageStep("payment-received", "orderId"));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(
                process("bk-1", new Variable("orderId", "O-77"))));

        useCase.handle(new CorrelateMessageCommand("payment-received", "O-77", List.of()));

        verify(completeMessageStepHandler).handle(any());
    }

    @Test
    void ignoresMessageWithMismatchedCorrelationKey() {
        var se = pendingSe(messageStep("payment-received", null));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("bk-1")));

        useCase.handle(new CorrelateMessageCommand("payment-received", "other-key", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }

    @Test
    void ignoresMessageWithDifferentName() {
        var se = pendingSe(messageStep("payment-received", null));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));

        useCase.handle(new CorrelateMessageCommand("something-else", "bk-1", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }

    @Test
    void ignoresNonMessageSteps() {
        var action = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, false, "t", null, null, 0, null, null, null, 0, 0, false, null);
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(pendingSe(action)));

        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }

    @Test
    void ignoresMessageWhenNoStepIsWaiting() {
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of());

        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }

    @Test
    void failsClosedWhenCorrelationExpressionCannotBeEvaluated() {
        // The referenced variable is missing: the key cannot be computed, so nothing matches.
        var se = pendingSe(messageStep("payment-received", "orderId"));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process("bk-1")));

        useCase.handle(new CorrelateMessageCommand("payment-received", "O-77", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }

    @Test
    void ignoresStepsNotYetStarted() {
        var se = pendingSe(messageStep("payment-received", null)).withStartedAt(null);
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));

        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }

    @Test
    void ignoresStepsWhoseProcessIsGone() {
        var se = pendingSe(messageStep("payment-received", null));
        when(stepExecutionRepository.findPendingOrRunning()).thenReturn(List.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.empty());

        useCase.handle(new CorrelateMessageCommand("payment-received", "bk-1", List.of()));

        verify(completeMessageStepHandler, never()).handle(any());
    }
}
