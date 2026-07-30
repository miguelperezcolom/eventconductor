package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SEND_MESSAGE steps (message throw) — a workflow emits a MessageReceived aimed at the
 * process whose correlation key matches, carrying only the variables named in
 * {@code messageVariables}, and completes immediately (fire-and-forget).
 *
 * <p>The receiver correlates with {@code "correlationExpression": "businessKey"} — a plain
 * variable seeded into the JEXL context ({@code process.businessKey} property access is
 * blocked by the RESTRICTED permissions in JEXLEvaluator).
 */
class SendMessageE2eTest extends AbstractE2eTest {

    @Test
    void senderWorkflowDeliversItsMessageToTheWaitingReceiver() {
        // The receiver waits on 'handoff', correlated by its businessKey.
        createProcess("send-message-receiver", "receiver-1");
        assertThat(step("receiver-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // The sender computes the correlation key from its 'targetKey' variable and only
        // ships the variables listed in messageVariables ('paymentId', not 'secret').
        createProcess("send-message", "sender-1",
                new Variable("targetKey", "receiver-1"),
                new Variable("paymentId", "P-9"),
                new Variable("secret", "do-not-send"));

        // The sender completes on its own: SEND_MESSAGE is fire-and-forget.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("sender-1", "send").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
            assertThat(process("sender-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });

        // The receiver absorbed the message: the wait completed, the process finished and
        // the messageVariables became process state — nothing else leaked across.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("receiver-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
            assertThat(process("receiver-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });
        assertThat(process("receiver-1").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("paymentId", "P-9"));
        assertThat(process("receiver-1").getVariables())
                .noneMatch(v -> "secret".equals(v.name()) || "targetKey".equals(v.name()));
    }

    @Test
    void sendStepWhoseKeyMatchesNobodyStillCompletesTheSender() {
        // Fire-and-forget: no receiver is waiting, the message is dropped on the
        // receiving side and the sender still completes.
        createProcess("send-message", "sender-2",
                new Variable("targetKey", "nobody-waits-here"),
                new Variable("paymentId", "P-1"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("sender-2", "send").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
            assertThat(process("sender-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });
    }
}
