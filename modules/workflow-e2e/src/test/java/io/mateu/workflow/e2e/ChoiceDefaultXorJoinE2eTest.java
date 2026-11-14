package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An XOR join fed straight from the default (unguarded) branch of a CHOICE goes on when that CHOICE took
 * its other branch and the flow reaches the join some other way — here, through a second CHOICE's default.
 * A link from a CHOICE is a branch not taken when the CHOICE picks a sibling; that leaves the join's OTHER
 * links free to satisfy it. The JPA run is {@link ChoiceDefaultXorJoinJpaE2eTest}.
 */
class ChoiceDefaultXorJoinE2eTest extends AbstractE2eTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void theFirstChoicesDefaultReachesTheJoinDirectly() {
        worker.on("book", TestWorker.succeed(TestWorker.var("missing", "false")));
        worker.on("confirm", TestWorker.succeed());

        createProcess("choice-default-xor-join", "cdx-1");

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("cdx-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(worker.invocationsOf("ask")).isZero();
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
        assertThat(step("cdx-1", "booked").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void theJoinGoesOnFromTheSecondChoiceWhenTheFirstTookItsOtherBranch() {
        worker.on("book", TestWorker.succeed(TestWorker.var("missing", "true")));
        worker.on("ask", TestWorker.succeed(TestWorker.var("answer", "keep")));
        worker.on("confirm", TestWorker.succeed());

        createProcess("choice-default-xor-join", "cdx-2");

        // Before the fix the join was read as "a branch of all-booked" and nothing else: all-booked
        // had taken 'ask', so the join was a branch not taken, its link from guest-decision was never
        // looked at, and the process completed around it without running 'confirm'.
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("cdx-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(worker.invocationsOf("ask")).isEqualTo(1);
        assertThat(worker.invocationsOf("confirm")).isEqualTo(1);
        assertThat(step("cdx-2", "booked").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cdx-2", "cancelled").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void theSecondChoicesGuardedBranchStillKeepsTheJoinOut() {
        worker.on("book", TestWorker.succeed(TestWorker.var("missing", "true")));
        worker.on("ask", TestWorker.succeed(TestWorker.var("answer", "cancel")));
        worker.on("confirm", TestWorker.succeed());

        createProcess("choice-default-xor-join", "cdx-3");

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("cdx-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("cdx-3", "cancelled").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cdx-3", "booked").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("confirm")).isZero();
    }
}
