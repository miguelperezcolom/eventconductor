package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-GUARD-01..04 — a condition on one incoming link, rather than on the step.
 *
 * <p>The `link-guards` definition joins two branches into `ship`, and only one of the two links
 * carries a guard. A guard written on the step could not say that: it would gate `ship` however it
 * was reached, which is a different statement about a different thing.
 *
 * <p>The step waits for all of its links, so a guard that is false holds it. "Wait for all of them"
 * is read literally: a link whose condition never comes true never satisfies, and the step never
 * runs. The alternative — quietly not requiring that branch — would let a step proceed having
 * waited for less than its author wrote. Guards are evaluated against the process variables on
 * every pass, so the answer follows the variables rather than being decided once.
 */
class PreconditionGuardE2eTest extends AbstractE2eTest {

    private void bothBranchesSucceed() {
        worker.on("cheap", TestWorker.succeed());
        worker.on("pricey", TestWorker.succeed());
        worker.on("ship", TestWorker.succeed());
    }

    /** E2E-GUARD-01 — the guarded link holds, so the step runs. */
    @Test
    void aStepRunsWhenTheGuardOnItsLinkHolds() {
        bothBranchesSucceed();

        createProcess("link-guards", "guard-1", new Variable("amount", "500"));

        assertThat(process("guard-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(step("guard-1", "ship").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    /**
     * E2E-GUARD-02 — the guard on one link is false, so that link is not satisfied and the step
     * waits, even though the step it names completed and the other link is clear.
     */
    @Test
    void aFalseGuardHoldsTheStepEvenThoughItsStepCompleted() {
        bothBranchesSucceed();

        createProcess("link-guards", "guard-2", new Variable("amount", "50"));

        assertThat(step("guard-2", "pricey").getStatus())
                .as("the step the guarded link names did complete")
                .isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("guard-2", "cheap").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("guard-2", "ship").getStatus())
                .as("but the link it arrives by is not satisfied, so ship has not run")
                .isEqualTo(StepExecutionStatus.CREATED);
        assertThat(process("guard-2").getStatus()).isNotEqualTo(ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("ship")).isZero();
    }

    /**
     * E2E-GUARD-03 — the held step is not tidied away. The engine wraps a process up when nothing
     * can run and nothing is in flight: it cancels what is left and completes. A step held by a
     * guard looks exactly like that from the outside, and being swept up by it would turn "this
     * link is not satisfied" into "this step is cancelled and the process is finished" — the
     * opposite of what the guard says.
     *
     * <p>So the process stays open, waiting, which is what was asked for. Worth knowing what that
     * costs: with nothing else running there is nothing left inside the engine to change the
     * variable the guard reads, so this process waits for an operator, and it is not visible to
     * the stalled-step gauge, which counts steps that started and this one never did.
     */
    @Test
    void theHeldStepIsNotCancelledAndTheProcessIsNotCompletedAroundIt() {
        bothBranchesSucceed();

        createProcess("link-guards", "guard-3", new Variable("amount", "50"));

        assertThat(step("guard-3", "ship").getStatus())
                .as("held, not cancelled")
                .isEqualTo(StepExecutionStatus.CREATED);
        assertThat(step("guard-3", "end").getStatus())
                .as("and the step after it is left alone too")
                .isEqualTo(StepExecutionStatus.CREATED);
        assertThat(process("guard-3").getStatus())
                .as("the process is still going, not completed around the step it is waiting for")
                .isEqualTo(ProcessStatus.RUNNING);
    }

    /**
     * A guard on a link is not the same thing as the step-level {@code preconditionExpression},
     * which is older and means something else: that one skips the step and lets the process finish
     * (E2E-COND-02). Both still work, and definitions written before links could carry guards keep
     * the behaviour they were written for.
     */
    @Test
    void theStepLevelExpressionStillSkipsRatherThanHolds() {
        worker.on("gate", TestWorker.succeed());
        worker.on("premium", TestWorker.succeed());

        createProcess("conditional", "guard-4", new Variable("tier", "basic"));

        assertThat(process("guard-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
