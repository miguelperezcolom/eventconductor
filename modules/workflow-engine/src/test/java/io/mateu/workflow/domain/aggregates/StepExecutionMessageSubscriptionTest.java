package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WAIT_FOR_MESSAGE step stores what it is waiting for so an arriving message can find it by
 * index. These pin both halves of that: what gets armed at start, and — because the correlation
 * key derives from process variables that keep moving while the step waits — that rearming keeps
 * it equal to what evaluating the expression on arrival would have produced.
 */
class StepExecutionMessageSubscriptionTest {

    private Step waitStep(String messageName, String correlationExpression) {
        return new Step("s1", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null, null, null, null, false, null, null, null, null, null, 0, null, messageName, correlationExpression, null, 0, 0, false, null, 0, null);
    }

    private Step actionStep() {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, 30_000, 0, false, null, 0, null);
    }

    private Process process(String businessKey, Variable... variables) {
        return Process.builder().id("p-1").businessKey(businessKey).variables(List.of(variables)).build();
    }

    private StepExecution created(Step step) {
        return StepExecution.create(step, "p-1", 0);
    }

    @Test
    void armsTheBusinessKeyWhenTheStepDefinesNoExpression() {
        // Legacy shape: steps persisted before the rename carry no correlationExpression.
        var stepExecution = created(waitStep("payment-received", null)).start(process("bk-1"));

        assertThat(stepExecution.getAwaitingMessageName()).isEqualTo("payment-received");
        assertThat(stepExecution.getAwaitingCorrelationKey()).isEqualTo("bk-1");
    }

    @Test
    void armsTheExpressionEvaluatedOverProcessVariables() {
        var stepExecution = created(waitStep("payment-received", "orderId"))
                .start(process("bk-1", new Variable("orderId", "O-77")));

        assertThat(stepExecution.getAwaitingCorrelationKey()).isEqualTo("O-77");
    }

    @Test
    void armsNoKeyWhenTheExpressionCannotBeEvaluated() {
        // Fail closed: the variable the expression reads is not on the process. A null key
        // matches nothing, which is what evaluating on arrival used to produce.
        var stepExecution = created(waitStep("payment-received", "orderId")).start(process("bk-1"));

        assertThat(stepExecution.getAwaitingMessageName()).isEqualTo("payment-received");
        assertThat(stepExecution.getAwaitingCorrelationKey()).isNull();
    }

    @Test
    void armsNothingForAStepThatIsNotAMessageWait() {
        var stepExecution = created(actionStep()).start(process("bk-1"));

        assertThat(stepExecution.getAwaitingMessageName()).isNull();
        assertThat(stepExecution.getAwaitingCorrelationKey()).isNull();
    }

    @Test
    void armsNothingBeforeTheStepStarts() {
        var stepExecution = created(waitStep("payment-received", null));

        assertThat(stepExecution.getAwaitingMessageName()).isNull();
    }

    @Test
    void rearmingFollowsTheVariableTheExpressionReads() {
        // This is the contract that materialising the key had to preserve: a parallel branch
        // changing orderId changes what this step is waiting for.
        var waiting = created(waitStep("payment-received", "orderId"))
                .start(process("bk-1", new Variable("orderId", "O-77")));

        var rearmed = waiting.rearmedFor(process("bk-1", new Variable("orderId", "O-99")));

        assertThat(rearmed.getAwaitingCorrelationKey()).isEqualTo("O-99");
    }

    @Test
    void rearmingReturnsTheSameInstanceWhenNothingMoved() {
        // The service saves only what changed, so "unchanged" has to be identity-detectable.
        var waiting = created(waitStep("payment-received", "orderId"))
                .start(process("bk-1", new Variable("orderId", "O-77")));

        assertThat(waiting.rearmedFor(process("bk-1", new Variable("orderId", "O-77"))))
                .isSameAs(waiting);
    }

    @Test
    void rearmingClearsTheSubscriptionOnceTheStepIsNoLongerWaiting() {
        var waiting = created(waitStep("payment-received", null)).start(process("bk-1"));
        waiting.updateStatus(StepExecutionStatus.COMPLETED);

        var rearmed = waiting.rearmedFor(process("bk-1"));

        assertThat(rearmed.getAwaitingMessageName()).isNull();
        assertThat(rearmed.getAwaitingCorrelationKey()).isNull();
    }

    @Test
    void rearmingLeavesTheDeadlineAlone() {
        var waiting = created(waitStep("payment-received", "orderId"))
                .start(process("bk-1", new Variable("orderId", "O-77")));

        var rearmed = waiting.rearmedFor(process("bk-1", new Variable("orderId", "O-99")));

        assertThat(rearmed.getDeadlineAt()).isEqualTo(waiting.getDeadlineAt());
        assertThat(rearmed.getStartedAt()).isEqualTo(waiting.getStartedAt());
    }

    @Test
    void rearmingArmsAStepThatStartedBeforeTheFieldsExisted() {
        // What the boot-time runner relies on: a step rehydrated without a subscription gets
        // one from the state it already carries.
        var legacy = StepExecution.builder()
                .id("legacy").processId("p-1")
                .stepJson(io.mateu.core.infra.JsonSerializer.toJson(waitStep("payment-received", null)))
                .status(StepExecutionStatus.PENDING)
                .startedAt(java.time.LocalDateTime.now())
                .variables(List.of())
                .build();

        var rearmed = legacy.rearmedFor(process("bk-1"));

        assertThat(rearmed.getAwaitingMessageName()).isEqualTo("payment-received");
        assertThat(rearmed.getAwaitingCorrelationKey()).isEqualTo("bk-1");
    }
}
