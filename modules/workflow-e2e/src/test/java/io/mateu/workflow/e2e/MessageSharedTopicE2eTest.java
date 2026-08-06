package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-shard message routing, exercised end to end with {@code workflow.messages.shared-topic=true}.
 * The switch changes where an external message is published (the shared {@code messages} topic instead of
 * a shard's {@code upstream}); this proves the switch does not break correlation — a message dispatched
 * through {@link MessageDispatcher} still wakes the waiting step and merges its payload.
 *
 * <p>Single-node here (embedded), so the shared topic and the node's own upstream are the same reach; the
 * true two-shard case (a send on shard A waking a waiter on shard B over one shared topic) is a
 * distributed test. This guards the routing itself: enabling the flag must be lossless.
 */
@TestPropertySource(properties = "workflow.messages.shared-topic=true")
class MessageSharedTopicE2eTest extends AbstractE2eTest {

    @Autowired MessageDispatcher messageDispatcher;

    @Test
    void aMessageDispatchedThroughTheSharedTopicStillWakesTheWaiter() {
        worker.on("after", TestWorker.succeed());

        createProcess("message", "shared-1");
        assertThat(step("shared-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // Routed by the dispatcher; with the flag on it takes the shared-messages path.
        messageDispatcher.dispatch(new MessageReceived("payment-received", "shared-1",
                List.of(new Variable("paymentId", "P-9"))));

        assertThat(step("shared-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("shared-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("shared-1").getVariables())
                .contains(new io.mateu.workflow.domain.aggregates.Variable("paymentId", "P-9"));
        assertThat(worker.invocationsOf("after")).isEqualTo(1);
    }

    @Test
    void aMismatchedKeyOnTheSharedTopicIsDroppedNotBuffered() {
        worker.on("after", TestWorker.succeed());

        createProcess("message", "shared-2");

        messageDispatcher.dispatch(new MessageReceived("payment-received", "nobody", List.of()));
        assertThat(step("shared-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        messageDispatcher.dispatch(new MessageReceived("payment-received", "shared-2", List.of()));
        assertThat(process("shared-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
