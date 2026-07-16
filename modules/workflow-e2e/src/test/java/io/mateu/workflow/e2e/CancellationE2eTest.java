package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-CANC-01/02. */
class CancellationE2eTest extends AbstractE2eTest {

    @Test
    void cancellingMidFlightStopsTheFlow() {
        worker.on("wait", TestWorker.deferForever()); // first step stays in flight

        createProcess("cancel", "can-1");
        assertThat(step("can-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        cancelProcessUseCase.handle(new CancelProcessCommand(process("can-1").getId()));

        assertThat(process("can-1").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(step("can-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        // The later step must never have been dispatched during cancellation.
        assertThat(worker.invocationsOf("next")).isEqualTo(0);
        assertThat(step("can-1", "next").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void lateWorkerReportAfterCancellationIsIgnored() {
        // Capture the in-flight task so we can simulate a late completion after cancel.
        worker.on("wait", TestWorker.deferForever());
        createProcess("cancel", "can-2");
        var inFlight = worker.received().stream()
                .filter(r -> "wait".equals(r.stepId())).findFirst().orElseThrow();

        cancelProcessUseCase.handle(new CancelProcessCommand(process("can-2").getId()));
        assertThat(step("can-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);

        // A late COMPLETED from the worker must not resurrect the cancelled step.
        updateStepExecutionUseCase.handle(
                new io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand(
                        inFlight.taskExecutionId(), java.util.List.of(), "", StepExecutionStatus.COMPLETED));

        assertThat(step("can-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(process("can-2").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
    }
}
