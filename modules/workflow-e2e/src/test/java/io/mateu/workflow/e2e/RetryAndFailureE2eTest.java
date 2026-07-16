package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-RET-01..05. */
class RetryAndFailureE2eTest extends AbstractE2eTest {

    @Test
    void stepRetriesThenSucceeds() {
        // retries=2, worker fails twice then succeeds → 3 invocations, process COMPLETED.
        worker.on("flaky", TestWorker.failThenSucceed(2));

        createProcess("retry", "ret-1");

        assertThat(worker.invocationsOf("flaky")).isEqualTo(3);
        assertThat(process("ret-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void retriesExhaustedMarksProcessAsErrorAndBlocksSuccessors() {
        worker.on("flaky", TestWorker.fail());

        createProcess("retry", "ret-2");

        // retries=2 → 1 initial + 2 retries = 3 attempts, then give up.
        assertThat(worker.invocationsOf("flaky")).isEqualTo(3);
        assertThat(step("ret-2", "flaky").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("ret-2").getStatus())
                .as("a process with an exhausted step must be ERROR, never COMPLETED")
                .isEqualTo(ProcessStatus.ERROR);
        assertThat(step("ret-2", "end").getStatus()).isNotEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void manualStepRetryResumesFailedProcess() {
        worker.on("flaky", TestWorker.fail());
        createProcess("retry", "ret-3");
        assertThat(process("ret-3").getStatus()).isEqualTo(ProcessStatus.ERROR);

        // Operator fixes the downstream system: now the step succeeds. Retry it.
        worker.on("flaky", TestWorker.succeed());
        retryStepExecutionUseCase.handle(
                new io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionCommand(
                        step("ret-3", "flaky").id()));

        assertThat(process("ret-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void manualProcessRetryResetsAllFailedSteps() {
        worker.on("flaky", TestWorker.fail());
        createProcess("retry", "ret-4");
        assertThat(process("ret-4").getStatus()).isEqualTo(ProcessStatus.ERROR);

        worker.on("flaky", TestWorker.succeed());
        retryProcessUseCase.handle(
                new io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand(
                        process("ret-4").getId()));

        assertThat(process("ret-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void retryingAStepOfACancelledProcessIsANoOp() {
        worker.on("flaky", TestWorker.fail());
        createProcess("retry", "ret-5");
        cancelProcessUseCase.handle(
                new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(
                        process("ret-5").getId()));
        assertThat(process("ret-5").getStatus()).isEqualTo(ProcessStatus.CANCELLED);

        worker.on("flaky", TestWorker.succeed());
        retryStepExecutionUseCase.handle(
                new io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionCommand(
                        step("ret-5", "flaky").id()));

        assertThat(process("ret-5").getStatus())
                .as("retry must not revive a cancelled process")
                .isEqualTo(ProcessStatus.CANCELLED);
    }
}
