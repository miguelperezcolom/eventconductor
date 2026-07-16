package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-PAR-01, E2E-END-01. */
class ParallelFlowE2eTest extends AbstractE2eTest {

    @Test
    void parallelStepsBothExecuteAndProcessCompletes() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());

        createProcess("parallel", "par-1");

        assertThat(worker.invocationsOf("a")).isEqualTo(1);
        assertThat(worker.invocationsOf("b")).isEqualTo(1);
        assertThat(process("par-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
