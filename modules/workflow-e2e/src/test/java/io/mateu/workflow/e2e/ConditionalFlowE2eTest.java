package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-COND-01/02/03. */
class ConditionalFlowE2eTest extends AbstractE2eTest {

    @Test
    void guardedStepRunsWhenExpressionTrue() {
        worker.on("gate", TestWorker.succeed());
        worker.on("premium", TestWorker.succeed());

        createProcess("conditional", "cond-1", new Variable("tier", "premium"));

        assertThat(worker.invocationsOf("premium")).isEqualTo(1);
        assertThat(process("cond-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void guardedStepSkippedWhenExpressionFalse() {
        worker.on("gate", TestWorker.succeed());
        worker.on("premium", TestWorker.succeed());

        createProcess("conditional", "cond-2", new Variable("tier", "basic"));

        assertThat(worker.invocationsOf("premium")).isEqualTo(0);
        assertThat(process("cond-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void guardReferencingMissingVariableFailsClosed() {
        // No "tier" variable at all → JEXL strict evaluation errors → step must NOT run.
        worker.on("gate", TestWorker.succeed());
        worker.on("premium", TestWorker.succeed());

        createProcess("conditional", "cond-3");

        assertThat(worker.invocationsOf("premium")).isEqualTo(0);
        assertThat(process("cond-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
