package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-PAR-01, E2E-END-01. Parallelism is expressed with FORK/JOIN and preconditions (the
 * old {@code parallel} flag is gone): the fixture is START → FORK → (a, b) → JOIN → END.
 */
class ParallelFlowE2eTest extends AbstractE2eTest {

    @Test
    void parallelStepsBothExecuteAndProcessCompletes() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());

        createProcess("parallel", "par-1");

        assertThat(worker.invocationsOf("a")).isEqualTo(1);
        assertThat(worker.invocationsOf("b")).isEqualTo(1);
        assertThat(step("par-1", "join").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("par-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void oneBranchInFlightDoesNotStopTheOtherFromRunning() {
        worker.on("a", TestWorker.deferForever());
        worker.on("b", TestWorker.succeed());

        createProcess("parallel", "par-2");

        // b ran to completion while a is still in flight; the join keeps waiting for a.
        assertThat(worker.invocationsOf("b")).isEqualTo(1);
        assertThat(step("par-2", "b").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("par-2", "a").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(step("par-2", "join").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(process("par-2").getStatus()).isNotEqualTo(ProcessStatus.COMPLETED);
    }
}
