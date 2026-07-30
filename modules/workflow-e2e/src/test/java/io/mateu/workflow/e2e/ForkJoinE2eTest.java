package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FORK/JOIN as real control-flow nodes: START → FORK → two ACTION branches → JOIN →
 * ACTION → END. The FORK fans out by its successors' preconditions; the JOIN's barrier is
 * its {@code preconditionStepIds} — it must not pass until EVERY branch has completed.
 */
class ForkJoinE2eTest extends AbstractE2eTest {

    @Test
    void bothBranchesRunAndTheFlowContinuesPastTheJoin() {
        worker.on("branch-a", TestWorker.succeed());
        worker.on("branch-b", TestWorker.succeed());
        worker.on("after-join", TestWorker.succeed());

        createProcess("fork-join", "fj-1");

        assertThat(worker.invocationsOf("branch-a")).isEqualTo(1);
        assertThat(worker.invocationsOf("branch-b")).isEqualTo(1);
        assertThat(worker.invocationsOf("after-join")).isEqualTo(1);
        // The control-flow nodes complete instantly without dispatching worker tasks.
        assertThat(worker.invocationsOf("start")).isEqualTo(0);
        assertThat(worker.invocationsOf("fork")).isEqualTo(0);
        assertThat(worker.invocationsOf("join")).isEqualTo(0);
        assertThat(step("fj-1", "join").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("fj-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void joinWaitsUntilEveryBranchHasCompleted() {
        // branch-b stays in flight: the join must hold the barrier even though branch-a
        // completed, and after-join must not start.
        worker.on("branch-a", TestWorker.succeed());
        worker.on("branch-b", TestWorker.deferForever());
        worker.on("after-join", TestWorker.succeed());

        createProcess("fork-join", "fj-2");

        assertThat(step("fj-2", "branch-a").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("fj-2", "branch-b").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(step("fj-2", "join").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(worker.invocationsOf("after-join")).isEqualTo(0);
        assertThat(process("fj-2").getStatus()).isNotEqualTo(ProcessStatus.COMPLETED);

        // The lagging branch reports back → the barrier opens and the flow runs to the end.
        var inFlight = worker.received().stream()
                .filter(r -> "branch-b".equals(r.stepId())).findFirst().orElseThrow();
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                inFlight.taskExecutionId(), List.of(), "", StepExecutionStatus.COMPLETED));

        assertThat(step("fj-2", "join").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("after-join")).isEqualTo(1);
        assertThat(process("fj-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void branchesRunConcurrentlyNotSerialized() {
        // Dataflow: while branch-b is still in flight, branch-a must already have been
        // dispatched (previously the active-step break serialized unrelated branches).
        worker.on("branch-a", TestWorker.deferForever());
        worker.on("branch-b", TestWorker.deferForever());

        createProcess("fork-join", "fj-3");

        assertThat(worker.invocationsOf("branch-a")).isEqualTo(1);
        assertThat(worker.invocationsOf("branch-b")).isEqualTo(1);
        assertThat(step("fj-3", "branch-a").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(step("fj-3", "branch-b").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }
}
