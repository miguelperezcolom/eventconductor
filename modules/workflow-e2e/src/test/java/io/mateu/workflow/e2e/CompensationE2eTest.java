package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-COMP-01. The {@code compensation} definition is a two-step saga: {@code charge} (compensated
 * by {@code refund}) then {@code ship} (compensated by {@code unship}). {@code charge} succeeds and
 * {@code ship} fails, so the rollback undoes the step that <b>committed</b> work — charge, via
 * refund — and never the step that failed. The compensations declare no preconditions of their own:
 * a compensation is declared on the step it undoes, and the rollback pipeline starts it.
 */
class CompensationE2eTest extends AbstractE2eTest {

    @Test
    void aLaterFailureCompensatesTheCompletedStepButNotTheFailedOne() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.fail());       // retries=0 → fails immediately, triggers rollback
        worker.on("refund", TestWorker.succeed());  // charge's compensation
        worker.on("unship", TestWorker.succeed());  // wired, but ship failed so it must never run

        createProcess("compensation", "comp-1");

        assertThat(step("comp-1", "charge").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("comp-1", "ship").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        // The completed step is compensated…
        assertThat(worker.invocationsOf("refund"))
                .as("the compensation of a completed step must run when a later step fails")
                .isEqualTo(1);
        assertThat(step("comp-1", "refund").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // …the failed step is not: it committed nothing to undo.
        assertThat(worker.invocationsOf("unship"))
                .as("the compensation of the failed step must never run")
                .isZero();
        // The process rolled back cleanly to COMPENSATED, not left in ERROR.
        assertThat(process("comp-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }

    @Test
    void aRolledBackProcessIsFinished_notLeftLookingLikeItIsStillGoing() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.fail());
        worker.on("refund", TestWorker.succeed());
        worker.on("unship", TestWorker.succeed());

        createProcess("compensation", "comp-3");

        // The rollback ran to the end, so the process is as finished as one that completed.
        assertThat(process("comp-3").getCompletionPercentage()).isEqualTo(100);
        assertThat(process("comp-3").getFinished()).isNotNull();
        // And nothing is left looking like it is waiting its turn: 'end' never ran and never will.
        assertThat(step("comp-3", "end").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        // The step that failed keeps its ERROR — it is the record of why this happened.
        assertThat(step("comp-3", "ship").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
    }

    @Test
    void aFailedCompensationReachesCompensationFailed_notSilentlyWedged() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.fail());     // triggers rollback of the completed charge
        worker.on("refund", TestWorker.fail());   // the compensation itself fails (retries=0)

        createProcess("compensation", "comp-4");

        assertThat(step("comp-4", "ship").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(step("comp-4", "refund").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        // The whole point of the fix: a saga whose compensation fails must reach the distinct,
        // sticky COMPENSATION_FAILED terminal — never left in ERROR, half-rolled-back and silent.
        assertThat(process("comp-4").getStatus())
                .as("a failed compensation must surface as COMPENSATION_FAILED, not a plain ERROR")
                .isEqualTo(ProcessStatus.COMPENSATION_FAILED);
        // It is terminal: finished is stamped and 'end' can never run.
        assertThat(process("comp-4").getFinished()).isNotNull();
        assertThat(step("comp-4", "end").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void aCompensationDoesNotRunWhenNothingWentWrong() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.succeed());
        worker.on("refund", TestWorker.succeed());
        worker.on("unship", TestWorker.succeed());

        createProcess("compensation", "comp-2");

        // The failure this guards against is a compensation wired into the happy path: it would
        // refund every successful charge, and — being an ordinary step — carry the flow on past
        // itself while it was at it.
        assertThat(worker.invocationsOf("refund")).isZero();
        assertThat(worker.invocationsOf("unship")).isZero();
        assertThat(step("comp-2", "refund").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(step("comp-2", "unship").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(process("comp-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
