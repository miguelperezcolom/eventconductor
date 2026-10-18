package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Every way a process can end frees the locks it holds — not only reaching END. A process that
 * fails inside its critical section, or is cancelled while in it, used to keep the key locked until
 * the lease reaper came round, wedging every other process on the same key: the release keyed off a
 * "process just errored" flag that the status recompute had already consumed by the time step-over
 * looked at it, and cancellation never released at all.
 */
class TerminalLockReleaseE2eTest extends AbstractE2eTest {

    private static Variable booking(String id) {
        return new Variable("bookingId", id);
    }

    @Test
    void aProcessThatErrorsInsideItsCriticalSectionReleasesTheLockAndAdmitsTheWaiter() {
        worker.on("work", TestWorker.deferForever());
        createProcess("lock-serialization", "lre-A", booking("LRE-1"));
        createProcess("lock-serialization", "lre-B", booking("LRE-1"));
        assertThat(step("lre-B", "lock").getStatus()).isEqualTo(StepExecutionStatus.WAITING_ON_LOCK);

        // A's critical section fails for good (no retries on "work"): A is ERROR...
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-A", "work").id(), List.of(), "boom", StepExecutionStatus.ERROR));
        assertThat(process("lre-A").getStatus()).isEqualTo(ProcessStatus.ERROR);

        // ...and B, parked behind it, is admitted into its own critical section.
        assertThat(step("lre-B", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("lre-B", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void aNewcomerTakesTheKeyOfAProcessThatErroredHoldingIt() {
        worker.on("work", TestWorker.fail());
        createProcess("lock-serialization", "lre-C", booking("LRE-2"));
        assertThat(process("lre-C").getStatus()).isEqualTo(ProcessStatus.ERROR);

        worker.on("work", TestWorker.deferForever());
        createProcess("lock-serialization", "lre-D", booking("LRE-2"));
        assertThat(step("lre-D", "lock").getStatus())
                .as("the errored holder must not keep the key locked")
                .isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void cancellingAHolderReleasesTheLockAndAdmitsTheWaiter() {
        worker.on("work", TestWorker.deferForever());
        createProcess("lock-serialization", "lre-E", booking("LRE-3"));
        createProcess("lock-serialization", "lre-F", booking("LRE-3"));
        assertThat(step("lre-F", "lock").getStatus()).isEqualTo(StepExecutionStatus.WAITING_ON_LOCK);

        cancelProcessUseCase.handle(new CancelProcessCommand(process("lre-E").getId()));
        assertThat(process("lre-E").getStatus()).isEqualTo(ProcessStatus.CANCELLED);

        assertThat(step("lre-F", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("lre-F", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void cancellingAWaiterDropsItFromTheQueue() {
        worker.on("work", TestWorker.deferForever());
        createProcess("lock-serialization", "lre-G", booking("LRE-4"));
        createProcess("lock-serialization", "lre-H", booking("LRE-4"));
        createProcess("lock-serialization", "lre-I", booking("LRE-4"));

        // H gives up waiting; when G finishes, the lock goes to I, not to the cancelled H.
        cancelProcessUseCase.handle(new CancelProcessCommand(process("lre-H").getId()));
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-G", "work").id(), List.of(), "", StepExecutionStatus.COMPLETED));

        assertThat(process("lre-G").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(step("lre-I", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("lre-H", "work").getStatus()).isNotEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void aProcessLevelLockIsReleasedWhenTheInstanceErrors() {
        worker.on("pwork", TestWorker.deferForever());
        createProcess("process-lock-serialization", "lre-P1", booking("LRE-RES-1"));
        createProcess("process-lock-serialization", "lre-P2", booking("LRE-RES-1"));
        assertThat(step("lre-P2", "pwork").getStatus()).isEqualTo(StepExecutionStatus.CREATED);

        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-P1", "pwork").id(), List.of(), "boom", StepExecutionStatus.ERROR));
        assertThat(process("lre-P1").getStatus()).isEqualTo(ProcessStatus.ERROR);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(step("lre-P2", "pwork").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
    }

    @Test
    void aProcessLevelLockIsReleasedWhenTheInstanceIsCancelled() {
        worker.on("pwork", TestWorker.deferForever());
        createProcess("process-lock-serialization", "lre-P3", booking("LRE-RES-2"));
        createProcess("process-lock-serialization", "lre-P4", booking("LRE-RES-2"));
        assertThat(step("lre-P4", "pwork").getStatus()).isEqualTo(StepExecutionStatus.CREATED);

        cancelProcessUseCase.handle(new CancelProcessCommand(process("lre-P3").getId()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(step("lre-P4", "pwork").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
    }

    @Test
    void aFailedSagaKeepsItsLockUntilItsRollbackIsDone() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.deferForever());
        worker.on("refund", TestWorker.deferForever());
        createProcess("lock-compensation", "lre-S1", booking("LRE-SAGA"));
        createProcess("lock-compensation", "lre-S2", booking("LRE-SAGA"));
        assertThat(step("lre-S2", "lock").getStatus()).isEqualTo(StepExecutionStatus.WAITING_ON_LOCK);

        // S1 fails after charging: it is ERROR and its refund is in flight...
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-S1", "ship").id(), List.of(), "boom", StepExecutionStatus.ERROR));
        assertThat(process("lre-S1").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(step("lre-S1", "refund").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // ...so the booking is still being undone: S2 must not enter its critical section yet.
        assertThat(step("lre-S2", "lock").getStatus())
                .as("the key stays locked while the holder's rollback runs")
                .isEqualTo(StepExecutionStatus.WAITING_ON_LOCK);

        // The refund completes, the saga is rolled back, and only then is S2 admitted.
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-S1", "refund").id(), List.of(), "", StepExecutionStatus.COMPLETED));
        assertThat(process("lre-S1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(step("lre-S2", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void aSagaWhoseRollbackFailsStillReleasesItsLock() {
        worker.on("charge", TestWorker.succeed());
        worker.on("ship", TestWorker.deferForever());
        worker.on("refund", TestWorker.deferForever());
        createProcess("lock-compensation", "lre-S3", booking("LRE-SAGA-2"));
        createProcess("lock-compensation", "lre-S4", booking("LRE-SAGA-2"));

        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-S3", "ship").id(), List.of(), "boom", StepExecutionStatus.ERROR));
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(
                step("lre-S3", "refund").id(), List.of(), "refund failed", StepExecutionStatus.ERROR));

        assertThat(process("lre-S3").getStatus()).isEqualTo(ProcessStatus.COMPENSATION_FAILED);
        assertThat(step("lre-S4", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
