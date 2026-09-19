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
 * End-to-end: two processes whose critical section is wrapped in a LOCK/UNLOCK keyed by the same
 * {@code bookingId} run one at a time, and the second is admitted only when the first releases —
 * the core serialization guarantee, exercised through the real engine (start → acquire → work →
 * release → END).
 *
 * <p>The worker defers the {@code work} task so the first holder keeps the lock while we observe the
 * second parked; completing the work by hand releases it and admits the waiter. Each test uses a
 * distinct booking key and business keys, since the memory-mode harness shares its repositories and
 * lock table across methods.
 */
class LockSerializationE2eTest extends AbstractE2eTest {

    private static Variable booking(String id) {
        return new Variable("bookingId", id);
    }

    private void completeWork(String businessKey) {
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step(businessKey, "work").id(), List.of(), "", StepExecutionStatus.COMPLETED));
    }

    @Test
    void twoProcessesOnTheSameKeyAreSerializedAndTheWaiterIsAdmittedFifo() {
        // The critical section hangs at the worker, so whoever holds the lock keeps it.
        worker.on("work", TestWorker.deferForever());

        // A takes the lock and sits in its critical section.
        createProcess("lock-serialization", "same-A", booking("SAME-1"));
        assertThat(step("same-A", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("same-A", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(process("same-A").getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(worker.invocationsOf("work")).isEqualTo(1);

        // B wants the same key: it must park at its LOCK step, its work never dispatched.
        createProcess("lock-serialization", "same-B", booking("SAME-1"));
        assertThat(step("same-B", "lock").getStatus()).isEqualTo(StepExecutionStatus.WAITING_ON_LOCK);
        assertThat(worker.invocationsOf("work")).as("B's work must not run while A holds the lock").isEqualTo(1);

        // A finishes its critical section: it releases the lock and completes, and B is admitted.
        completeWork("same-A");
        assertThat(process("same-A").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(step("same-B", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("same-B", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("work")).as("B's work runs once it holds the lock").isEqualTo(2);

        // B finishes too.
        completeWork("same-B");
        assertThat(process("same-B").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aDifferentKeyDoesNotContend() {
        worker.on("work", TestWorker.deferForever());

        createProcess("lock-serialization", "diff-A", booking("DIFF-1"));
        // A different booking is an independent lock: it acquires straight away and runs.
        createProcess("lock-serialization", "diff-C", booking("DIFF-2"));

        assertThat(step("diff-A", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(step("diff-C", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(step("diff-C", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("work")).isEqualTo(2);
    }
}
