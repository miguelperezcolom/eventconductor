package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-OPS-01..05 — Operator actions travel as events.
 *
 * <p>Pausing, resuming, cancelling and retrying used to run wherever the UI click or the MCP call
 * landed, which under partition ownership is not the pod that owns the process. They are now
 * published keyed by the process and carried out by the pod that owns it, so an operator action
 * goes through the same single writer as everything else instead of being the one path that needs
 * a lock to be safe.
 *
 * <p>In embedded mode the publisher dispatches in-process, so this asserts the behaviour
 * synchronously — the routing itself is a kafka-mode concern, covered by the keys in DIST-11.
 * What these pin is that each event reaches its use case at all: five handlers that nothing else
 * exercises.
 */
class OperatorActionsE2eTest extends AbstractE2eTest {

    private void request(DomainEvent event) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
    }

    /** E2E-OPS-01 — a pause request pauses the process. */
    @Test
    void pauseRequestPausesTheProcess() {
        worker.on("s1", TestWorker.deferForever());
        createProcess("sequential-3", "ops-1");

        request(new PauseProcessRequested(process("ops-1").getId()));

        assertThat(process("ops-1").getStatus()).isEqualTo(ProcessStatus.PAUSED);
    }

    /** E2E-OPS-02 — a resume request moves it on again. */
    @Test
    void resumeRequestResumesTheProcess() {
        // s1 stays in flight, or the process completes before there is anything to pause.
        worker.on("s1", TestWorker.deferForever());
        createProcess("sequential-3", "ops-2");
        var id = process("ops-2").getId();

        request(new PauseProcessRequested(id));
        assertThat(process("ops-2").getStatus()).isEqualTo(ProcessStatus.PAUSED);

        request(new ResumeProcessRequested(id));
        assertThat(process("ops-2").getStatus()).isNotEqualTo(ProcessStatus.PAUSED);
    }

    /** E2E-OPS-03 — a cancellation request cancels, addressed by process id. */
    @Test
    void cancellationRequestCancelsTheProcess() {
        worker.on("s1", TestWorker.deferForever());
        createProcess("sequential-3", "ops-3");

        request(new ProcessCancellationRequested(null, process("ops-3").getId()));

        assertThat(process("ops-3").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
    }

    /** E2E-OPS-04 — a process-retry request revives the failed steps. */
    @Test
    void retryProcessRequestRetriesTheFailedSteps() {
        worker.on("s1", TestWorker.fail());
        createProcess("sequential-3", "ops-4");
        assertThat(process("ops-4").getStatus()).isEqualTo(ProcessStatus.ERROR);

        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        request(new RetryProcessRequested(process("ops-4").getId()));

        assertThat(process("ops-4").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    /** E2E-OPS-05 — a step-retry request revives that one step, carrying its process for routing. */
    @Test
    void retryStepRequestRetriesThatStep() {
        worker.on("s1", TestWorker.fail());
        createProcess("sequential-3", "ops-5");
        var failed = step("ops-5", "s1");
        assertThat(failed.getStatus()).isEqualTo(StepExecutionStatus.ERROR);

        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        request(new RetryStepExecutionRequested(failed.id(), failed.getProcessId()));

        assertThat(step("ops-5", "s1").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }
}
