package io.mateu.workflow.application.services;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.StepExecutionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageSubscriptionServiceTest {

    @Mock StepExecutionRepository stepExecutionRepository;

    @InjectMocks MessageSubscriptionService service;

    private Step waitStep(String correlationExpression) {
        return new Step("s1", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null, null, null, null, false, null, null, null, null, null, 0, null, "payment-received", correlationExpression, null, 0, 0, false, null, 0, null);
    }

    private Process process(Variable... variables) {
        return Process.builder().id("p-1").businessKey("bk-1").variables(List.of(variables)).build();
    }

    private StepExecution waiting(String correlationExpression, Process on) {
        return StepExecution.create(waitStep(correlationExpression), "p-1", 0).start(on);
    }

    @Test
    void rewritesTheKeyOfAStepWhoseVariableChanged() {
        var step = waiting("orderId", process(new Variable("orderId", "O-77")));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(step));

        service.rearm(process(new Variable("orderId", "O-99")));

        var saved = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(saved.capture());
        assertThat(saved.getValue().getAwaitingCorrelationKey()).isEqualTo("O-99");
    }

    @Test
    void writesNothingWhenNoKeyMoved() {
        // The common case by far — every worker completion passes through here, and almost
        // none of them touch a variable some sibling is correlating on.
        var step = waiting("orderId", process(new Variable("orderId", "O-77")));
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(step));

        service.rearm(process(new Variable("orderId", "O-77")));

        verify(stepExecutionRepository, never()).save(any());
    }

    @Test
    void looksOnlyAtTheStepsOfTheProcessThatChanged() {
        service.rearm(process());

        verify(stepExecutionRepository).findPendingOrRunningByProcessId("p-1");
        verify(stepExecutionRepository, never()).findPendingOrRunning();
    }

    @Test
    void ignoresStepsThatAreNotWaitingForAMessage() {
        var action = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var step = StepExecution.builder()
                .id("se-1").processId("p-1")
                .stepJson(JsonSerializer.toJson(action))
                .status(StepExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .variables(List.of())
                .build();
        when(stepExecutionRepository.findPendingOrRunningByProcessId("p-1")).thenReturn(List.of(step));

        service.rearm(process());

        verify(stepExecutionRepository, never()).save(any());
    }
}
