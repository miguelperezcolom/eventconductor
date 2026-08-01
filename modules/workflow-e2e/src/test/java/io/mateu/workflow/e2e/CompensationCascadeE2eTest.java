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
 * every executed rollbackable step run sequentially in reverse execution order, and the
 * process ends COMPENSATED.
 */
class CompensationCascadeE2eTest extends AbstractE2eTest {

    @Test
    void compensatesAllExecutedStepsInReverseOrder() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        worker.on("c", TestWorker.fail());          // retries=0 → fails immediately
        worker.on("comp-a", TestWorker.succeed());
        worker.on("comp-b", TestWorker.succeed());
        worker.on("comp-c", TestWorker.succeed());

        createProcess("compensation-cascade", "cascade-1");

        // a and b ran and succeeded; c failed.
        assertThat(step("cascade-1", "a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "c").getStatus()).isEqualTo(StepExecutionStatus.ERROR);

        // Every executed rollbackable step was compensated.
        assertThat(step("cascade-1", "comp-a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "comp-b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("cascade-1", "comp-c").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        // …in reverse execution order: c, then b, then a were executed, so their compensations
        // run comp-c → comp-b → comp-a.
        List<String> compensationOrder = worker.received().stream()
                .map(TaskExecutionRequested::stepId)
                .filter(id -> id.startsWith("comp-"))
                .toList();
        assertThat(compensationOrder).containsExactly("comp-c", "comp-b", "comp-a");

        // The process rolled back cleanly.
        assertThat(process("cascade-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
    }
}
