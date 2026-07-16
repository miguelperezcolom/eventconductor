package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-MSG-01..05 and E2E-IDEM-03 — MESSAGE steps durably pause the process until a
 * matching external message arrives (message catch / signal semantics).
 */
class MessageE2eTest extends AbstractE2eTest {

    /** Delivers a MessageReceived through the engine's public upstream event surface. */
    private void sendMessage(String messageName, String correlationKey, Variable... variables) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new MessageReceived(messageName, correlationKey, List.of(variables))));
    }

    /** E2E-MSG-01 — happy path: the message correlated by businessKey resumes the flow and merges its variables. */
    @Test
    void messageStepPausesUntilMatchingMessageArrives() {
        worker.on("after", TestWorker.succeed());

        createProcess("message", "msg-1");

        // While waiting nothing is dispatched: the step sits PENDING with no worker involved.
        assertThat(step("msg-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("after")).isZero();

        sendMessage("payment-received", "msg-1", new Variable("paymentId", "P-9"));

        assertThat(step("msg-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("msg-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        // The message payload became process state, visible to the successor step.
        assertThat(process("msg-1").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("paymentId", "P-9"));
        assertThat(worker.invocationsOf("after")).isEqualTo(1);
    }

    /** E2E-MSG-02 — a message whose correlation key does not match leaves the process waiting. */
    @Test
    void messageWithMismatchedCorrelationKeyDoesNotAdvanceTheProcess() {
        worker.on("after", TestWorker.succeed());

        createProcess("message", "msg-2");

        sendMessage("payment-received", "someone-else");

        assertThat(step("msg-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("after")).isZero();

        // The correctly correlated message still gets through afterwards.
        sendMessage("payment-received", "msg-2");
        assertThat(process("msg-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    /** E2E-MSG-03 — a waiting MESSAGE step honors the step timeout: no message → TIMEOUT → process ERROR. */
    @Test
    void messageStepTimesOutWhenNoMessageArrives() {
        createProcess("message-timeout", "msg-3");

        assertThat(step("msg-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // The timeout scheduler (200ms scan, 500ms timeout) expires the wait and the
        // normal failure pipeline engages (retries: 0 → process ERROR).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("msg-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
            assertThat(process("msg-3").getStatus()).isEqualTo(ProcessStatus.ERROR);
        });
    }

    /** E2E-MSG-04 — correlationExpression correlates by a process variable instead of the businessKey. */
    @Test
    void messageCorrelatesByExpressionOverProcessVariables() {
        createProcess("message-expr", "msg-4",
                new Variable("orderId", "O-77"));

        // The expression replaces the default businessKey correlation entirely.
        sendMessage("payment-received", "msg-4");
        assertThat(step("msg-4", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        sendMessage("payment-received", "O-77");
        assertThat(step("msg-4", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("msg-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    /** E2E-MSG-05 — a message that matches no waiting step is ignored, not buffered. */
    @Test
    void messageArrivingBeforeTheStepWaitsIsIgnoredNotBuffered() {
        worker.on("after", TestWorker.succeed());

        // Nobody is waiting yet: this delivery is dropped (documented contract).
        sendMessage("payment-received", "msg-5");

        createProcess("message", "msg-5");

        // The early message was NOT buffered — the step is still waiting.
        assertThat(step("msg-5", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // A redelivery once the step is waiting resumes the flow.
        sendMessage("payment-received", "msg-5");
        assertThat(process("msg-5").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    /** E2E-IDEM-03 — duplicate message delivery completes the step exactly once. */
    @Test
    void duplicateMessageDeliveryIsIgnored() {
        worker.on("after", TestWorker.succeed());

        createProcess("message", "msg-6");

        sendMessage("payment-received", "msg-6", new Variable("paymentId", "P-1"));
        sendMessage("payment-received", "msg-6", new Variable("paymentId", "P-1"));

        assertThat(process("msg-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("after")).isEqualTo(1);
    }
}
