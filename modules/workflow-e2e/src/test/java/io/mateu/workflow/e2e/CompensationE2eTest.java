package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-COMP-01. The {@code compensation} definition declares {@code refund} with no preconditions
 * at all — a compensation is declared on the step it undoes, and the rollback pipeline starts it,
 * so it needs no way in of its own. ({@code compensation-cascade} still anchors its compensations
 * with a permanently false guard, which is how this had to be written before and still works.)
 */
class CompensationE2eTest extends AbstractE2eTest {

    @Test
    void failingRollbackableStepTriggersCompensation() {
        worker.on("charge", TestWorker.fail());     // retries=0 → fails immediately
        worker.on("refund", TestWorker.succeed());  // compensation

        createProcess("compensation", "comp-1");

        assertThat(step("comp-1", "charge").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(worker.invocationsOf("refund"))
                .as("compensation step must run when a rollbackable step exhausts retries")
                .isEqualTo(1);
        assertThat(step("comp-1", "refund").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        // A single failed rollbackable step is the degenerate cascade: its compensation runs
        // and the process ends fully rolled back (COMPENSATED), not left in ERROR.
        assertThat(process("comp-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }

    @Test
    void aCompensationDoesNotRunWhenNothingWentWrong() {
        worker.on("charge", TestWorker.succeed());
        worker.on("refund", TestWorker.succeed());

        createProcess("compensation", "comp-2");

        // The failure this guards against is a compensation wired into the happy path: it would
        // refund every successful charge, and — being an ordinary step — carry the flow on past
        // itself while it was at it.
        assertThat(worker.invocationsOf("refund")).isZero();
        assertThat(step("comp-2", "refund").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(process("comp-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
