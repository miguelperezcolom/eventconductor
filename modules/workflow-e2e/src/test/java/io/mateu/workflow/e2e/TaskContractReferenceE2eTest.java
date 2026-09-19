package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a workflow that references a task contract by bare id (`task: greet`) has that
 * reference pinned to the contract's latest version when it loads, so the ACTION step dispatches
 * `greet@1` as its taskId — the contract loaded from a classpath `.ectask`.
 */
class TaskContractReferenceE2eTest extends AbstractE2eTest {

    @Test
    void aBareTaskReferenceIsPinnedToTheContractVersionOnDispatch() {
        worker.on("greet", TestWorker.succeed(var("message", "hi")));

        createProcess("task-ref", "tr-1");

        assertThat(process("tr-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);

        // The dispatched task carries the fully-qualified contract reference, not the bare id the
        // file was written with.
        var greet = worker.received().stream()
                .filter(r -> "greet".equals(r.stepId()))
                .findFirst().orElseThrow();
        assertThat(greet.taskId()).isEqualTo("greet@1");
    }
}
