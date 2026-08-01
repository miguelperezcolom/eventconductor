package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionSendMessageStartTest {

    private Process process(List<Variable> variables) {
        return Process.builder().id("p-1").businessKey("bk-1").variables(variables).build();
    }

    private Step sendStep(String messageName, String correlationExpression, List<String> messageVariables) {
        return new Step("send", "wd-1", StepType.SEND_MESSAGE, "Send message", null, null, null, null, false,
                null, null, null, null, null, 0, null, messageName, correlationExpression, messageVariables,
                0, 0, false, null, 0, null);
    }

    @Test
    void validSendStepEmitsMessageReceivedWithComputedKeyAndSelectedVariablesAndCompletes() {
        var step = sendStep("payment-received", "orderId", List.of("amount"));
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of(
                new Variable("orderId", "o-42"),
                new Variable("amount", "100"),
                new Variable("secret", "do-not-send"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof TaskExecutionRequested);
        var message = events.stream()
                .filter(e -> e instanceof MessageReceived)
                .map(e -> (MessageReceived) e)
                .findFirst().orElseThrow();
        assertThat(message.messageName()).isEqualTo("payment-received");
        assertThat(message.correlationKey()).isEqualTo("o-42");
        assertThat(message.variables()).hasSize(1);
        assertThat(message.variables().get(0).name()).isEqualTo("amount");
        assertThat(message.variables().get(0).value()).isEqualTo("100");
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Info.equals(log.messageType()));
    }

    @Test
    void sendStepCanCorrelateOnTheProcessBusinessKey() {
        // "businessKey" is seeded into the JEXL context as a plain variable (JEXL runs
        // RESTRICTED, so property access like process.businessKey is denied — see the
        // fail-closed test below).
        var step = sendStep("payment-received", "businessKey", null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        var message = se.popEvents().stream()
                .filter(e -> e instanceof MessageReceived)
                .map(e -> (MessageReceived) e)
                .findFirst().orElseThrow();
        assertThat(message.correlationKey()).isEqualTo("bk-1");
    }

    @Test
    void propertyAccessOnDomainObjectsFailsClosed() {
        // JEXLEvaluator runs with RESTRICTED permissions: property access on project
        // classes (process.businessKey) is denied by design — expressions are untrusted.
        // The supported idiom is the seeded plain variable "businessKey". A send whose
        // key cannot be evaluated must end in ERROR, not silently drop the message.
        var step = sendStep("payment-received", "process.businessKey", null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(se.popEvents()).noneMatch(e -> e instanceof MessageReceived);
    }

    @Test
    void sendStepWithoutCorrelationExpressionFailsThroughTheNormalPipeline() {
        var step = sendStep("payment-received", null, null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof MessageReceived);
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Error.equals(log.messageType()));
    }

    @Test
    void sendStepWithoutMessageNameFailsThroughTheNormalPipeline() {
        var step = sendStep(null, "orderId", null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of(new Variable("orderId", "o-42"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(se.popEvents()).noneMatch(e -> e instanceof MessageReceived);
    }

    @Test
    void correlationExpressionThatCannotBeEvaluatedFailsLoud() {
        // References a method that does not exist -> JEXL throws -> key null -> ERROR.
        var step = sendStep("payment-received", "process.noSuchMethod()", null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        var events = se.popEvents();
        assertThat(events).noneMatch(e -> e instanceof MessageReceived);
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && MessageType.Error.equals(log.messageType())
                && log.message().contains("could not be evaluated"));
    }

    @Test
    void correlationExpressionOnMissingContextYieldsNullKeyAndFails() {
        // "orderId" is not a process variable, so the expression evaluates to null.
        var step = sendStep("payment-received", "orderId", null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of(new Variable("other", "x"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(se.popEvents()).noneMatch(e -> e instanceof MessageReceived);
    }

    @Test
    void nullMessageVariablesSendsAMessageWithoutVariables() {
        var step = sendStep("payment-received", "orderId", null);
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of(new Variable("orderId", "o-42"), new Variable("amount", "100"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        var message = se.popEvents().stream()
                .filter(e -> e instanceof MessageReceived)
                .map(e -> (MessageReceived) e)
                .findFirst().orElseThrow();
        assertThat(message.variables()).isEmpty();
    }

    @Test
    void emptyMessageVariablesListSendsAMessageWithoutVariables() {
        var step = sendStep("payment-received", "orderId", List.of());
        var se = StepExecution.create(step, "p-1", 0);

        se.start(process(List.of(new Variable("orderId", "o-42"), new Variable("amount", "100"))));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        var message = se.popEvents().stream()
                .filter(e -> e instanceof MessageReceived)
                .map(e -> (MessageReceived) e)
                .findFirst().orElseThrow();
        assertThat(message.variables()).isEmpty();
    }
}
