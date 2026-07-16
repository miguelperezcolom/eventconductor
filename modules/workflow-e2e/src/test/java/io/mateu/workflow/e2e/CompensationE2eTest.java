package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-COMP-01. */
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
        assertThat(process("comp-1").getStatus()).isEqualTo(ProcessStatus.ERROR);
    }
}
