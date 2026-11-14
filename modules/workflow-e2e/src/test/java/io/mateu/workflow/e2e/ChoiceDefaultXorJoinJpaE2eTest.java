package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChoiceDefaultXorJoinE2eTest} through the database: an XOR join fed straight from a CHOICE's default
 * branch goes on through its other link when that CHOICE took another branch.
 */
class ChoiceDefaultXorJoinJpaE2eTest extends AbstractJpaE2eTest {

    @Test
    void theFirstChoicesDefaultReachesTheJoinDirectly() {
        worker.on("book", TestWorker.succeed(TestWorker.var("missing", "false")));
        worker.on("confirm", TestWorker.succeed());

        createProcess("choice-default-xor-join", "jcdx-1");

        awaitStatus("jcdx-1", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("ask")).isZero();
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
    }

    @Test
    void theJoinGoesOnFromTheSecondChoiceWhenTheFirstTookItsOtherBranch() {
        worker.on("book", TestWorker.succeed(TestWorker.var("missing", "true")));
        worker.on("ask", TestWorker.succeed(TestWorker.var("answer", "keep")));
        worker.on("confirm", TestWorker.succeed());

        createProcess("choice-default-xor-join", "jcdx-2");

        awaitStatus("jcdx-2", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("ask")).isEqualTo(1);
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
        assertThat(step("jcdx-2", "booked").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
