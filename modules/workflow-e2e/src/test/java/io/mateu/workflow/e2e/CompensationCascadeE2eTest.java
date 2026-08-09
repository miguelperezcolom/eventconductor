package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-COMP-02: a whole-process saga rollback. When a step finally fails, the compensations of
 * every step that <b>completed successfully</b> run sequentially in reverse execution order, and
 * the process ends COMPENSATED. The failed step is not compensated — it committed nothing to undo.
 */
class CompensationCascadeE2eTest extends AbstractE2eTest {

    @Test
    void compensatesTheCompletedStepsInReverseOrderButNotTheFailedOne() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        worker.on("c", TestWorker.fail());          // retries=0 → fails immediately
        worker.on("comp-a", TestWorker.succeed());
        worker.on("comp-b", TestWorker.succeed());
        worker.on("comp-c", TestWorker.succeed());   // wired, but must never be asked to run

        createProcess("compensation-cascade", "cascade-1");

        // a and b ran and succeeded; c failed.
        assertThat(step("cascade-1", "a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "c").getStatus()).isEqualTo(StepExecutionStatus.ERROR);

        // The two steps that succeeded were compensated; c's compensation was never started, because
        // c never committed anything to undo.
        assertThat(step("cascade-1", "comp-a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "comp-b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("comp-c")).isZero();

        // …in reverse execution order: b, then a were the completed steps, so their compensations
        // run comp-b → comp-a (and comp-c is absent).
        List<String> compensationOrder = worker.received().stream()
                .map(TaskExecutionRequested::stepId)
                .filter(id -> id.startsWith("comp-"))
                .toList();
        assertThat(compensationOrder).containsExactly("comp-b", "comp-a");

        // The process rolled back cleanly.
        assertThat(process("cascade-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }
}
