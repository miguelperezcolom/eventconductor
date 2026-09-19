package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.dtos.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end for the process-level lock: a definition declaring {@code processLock: bookingId}
 * serializes whole instances by that key. A second instance on the same key makes no progress at
 * all — not even its START runs — until the first finishes and it is admitted.
 */
class ProcessLockSerializationE2eTest extends AbstractE2eTest {

    private static Variable booking(String id) {
        return new Variable("bookingId", id);
    }

    private void completeWork(String businessKey) {
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step(businessKey, "pwork").id(), List.of(), "", StepExecutionStatus.COMPLETED));
    }

    @Test
    void wholeInstancesOnTheSameKeyRunOneAtATime() {
        worker.on("pwork", TestWorker.deferForever());

        // A acquires the process lock and runs its work.
        createProcess("process-lock-serialization", "proc-A", booking("RES-1"));
        assertThat(step("proc-A", "pwork").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("pwork")).isEqualTo(1);

        // B is held at the gate: nothing of it runs, not even START.
        createProcess("process-lock-serialization", "proc-B", booking("RES-1"));
        assertThat(step("proc-B", "start").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(step("proc-B", "pwork").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
        assertThat(worker.invocationsOf("pwork")).as("B must not run while A holds the process lock").isEqualTo(1);

        // A completes and releases the process lock; B is admitted and runs.
        completeWork("proc-A");
        assertThat(process("proc-A").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(step("proc-B", "pwork").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("pwork")).as("B runs once A releases the process lock").isEqualTo(2);

        completeWork("proc-B");
        assertThat(process("proc-B").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
