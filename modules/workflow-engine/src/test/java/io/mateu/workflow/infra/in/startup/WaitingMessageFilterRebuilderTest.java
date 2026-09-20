package io.mateu.workflow.infra.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.messagerouting.WaitingMessageFilter;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rebuilder feeds the filter the pairs of the steps that are currently waiting — and only those:
 * a step that is not a message wait, or one whose correlation key did not resolve, contributes
 * nothing.
 */
class WaitingMessageFilterRebuilderTest {

    private final StepExecutionRepository repository = mock(StepExecutionRepository.class);

    private Step waitStep(String messageName, String correlationExpression) {
        return new Step("s1", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null, null, null, null, false, null, null, null, null, null, 0, null, messageName, correlationExpression, null, 0, 0, false, null, 0, null);
    }

    private Step actionStep() {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, 30_000, 0, false, null, 0, null);
    }

    private Process process(String businessKey, Variable... variables) {
        return Process.builder().id("p-1").businessKey(businessKey).variables(List.of(variables)).build();
    }

    private StepExecution started(Step step, Process process) {
        return StepExecution.create(step, "p-1", 0).start(process);
    }

    @Test
    void populatesTheFilterFromTheWaitingStepsOnly() {
        var filter = new WaitingMessageFilter(true, 10_000, 0.01);
        when(repository.findPendingOrRunning()).thenReturn(List.of(
                started(waitStep("payment", "orderId"), process("bk", new Variable("orderId", "O-1"))),
                started(actionStep(), process("bk")),                 // not a message wait
                started(waitStep("payment", "orderId"), process("bk")), // armed name, unresolved key
                started(waitStep("shipment", "orderId"), process("bk", new Variable("orderId", "O-2")))));
        var rebuilder = new WaitingMessageFilterRebuilder(repository, filter);

        rebuilder.rebuildOnce();

        assertThat(filter.isReady()).isTrue();
        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isTrue();
        assertThat(filter.mightBeWaitingFor("shipment", "O-2")).isTrue();
        assertThat(filter.mightBeWaitingFor("payment", "O-2")).isFalse();
    }

    @Test
    void aDisabledFilterIsLeftUntouched() {
        var filter = new WaitingMessageFilter(false, 10_000, 0.01);
        when(repository.findPendingOrRunning()).thenReturn(List.of(
                started(waitStep("payment", "orderId"), process("bk", new Variable("orderId", "O-1")))));
        var rebuilder = new WaitingMessageFilterRebuilder(repository, filter);

        rebuilder.rebuildOnce();

        // Disabled: rebuild is a no-op, so it never becomes ready and always answers "maybe".
        assertThat(filter.isReady()).isFalse();
    }
}
