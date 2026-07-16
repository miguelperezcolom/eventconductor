package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/** E2E-SEQ-01/02, E2E-PRE-01. */
class SequentialFlowE2eTest extends AbstractE2eTest {

    @Test
    void sequentialHappyPathCompletesInOrder() {
        worker.on("s1", TestWorker.succeed(var("a", "1")));
        worker.on("s2", TestWorker.succeed(var("b", "2")));
        worker.on("s3", TestWorker.succeed(var("c", "3")));

        var process = createProcess("sequential-3", "seq-1");

        assertThat(process("seq-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("seq-1").getCompletionPercentage()).isEqualTo(100);
        assertThat(process("seq-1").getFinished()).isNotNull();
        assertThat(worker.invocationsOf("s1")).isEqualTo(1);
        assertThat(worker.invocationsOf("s2")).isEqualTo(1);
        assertThat(worker.invocationsOf("s3")).isEqualTo(1);
        assertThat(step("seq-1", "s3").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void variablesPropagateAcrossSteps() {
        worker.on("s1", TestWorker.succeed(var("greeting", "hello")));
        worker.on("s2", (req, cb, n) -> {
            // s2 must see the variable s1 wrote.
            boolean sees = req.variables().stream()
                    .anyMatch(v -> "greeting".equals(v.name()) && "hello".equals(v.value()));
            assertThat(sees).as("s2 sees variable written by s1").isTrue();
            cb.complete();
        });

        createProcess("sequential-3", "seq-2");

        assertThat(process("seq-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
