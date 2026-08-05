package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-EMB-02 — a worker that throws fails its step.
 *
 * <p>Reporting {@code ERROR} is the contract, and a worker that throws instead has not reported
 * anything. What used to happen then was nothing at all: the exception left the worker, the
 * engine parked the event, and the {@code StepExecution} stayed {@code PENDING} — waiting for a
 * reply from a worker that had already given up. Without a {@code timeout} on the step nothing
 * would ever look at it again, so the process stopped there, silently and permanently.
 *
 * <p>An unhandled throw is still not a reported failure, but it is a failure. The engine records
 * it as one so that everything downstream of a failed step — retries, compensation, the process
 * status — engages exactly as it does for a worker that reported properly.
 */
class WorkerThrowsE2eTest extends AbstractE2eTest {

    @Test
    void anExceptionEscapingTheWorkerFailsTheStepRatherThanFreezingIt() {
        worker.on("s1", (request, callback, invocation) -> {
            throw new IllegalStateException("the reservation service is not there");
        });

        createProcess("sequential-3", "throwing");

        assertThat(step("throwing", "s1").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("throwing").getStatus()).isEqualTo(ProcessStatus.ERROR);
        // The step that never got to run stays where it was, rather than being started anyway.
        assertThat(step("throwing", "s2").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
    }

    @Test
    void theExceptionIsRecordedOnTheProcess_notOnlyInTheApplicationLog() {
        worker.on("s1", (request, callback, invocation) -> {
            throw new IllegalStateException("the reservation service is not there");
        });

        createProcess("sequential-3", "throwing-logged");

        // Where an operator looks: the process's own errors, which is what the Errors tab and the
        // graph's hover card read. Before this the process recorded "Task status changed to ERROR"
        // and the reason existed only in the application's stdout.
        assertThat(errorsOf("throwing-logged"))
                .anySatisfy(message -> assertThat(message)
                        .contains("IllegalStateException", "the reservation service is not there"));
    }

    @Test
    void aThrowIsRetriedLikeAnyOtherFailureWhenTheStepAllowsIt() {
        worker.on("flaky", (request, callback, invocation) -> {
            if (invocation == 1) {
                throw new IllegalStateException("transient trouble in the worker");
            }
            callback.complete();
        });

        createProcess("retry", "throw-then-succeed");

        // Auto-retry is async (backoff): the second attempt runs after the scheduler wakes the
        // parked step, so the success is awaited rather than asserted inline.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("throw-then-succeed").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(worker.invocationsOf("flaky")).isEqualTo(2);
    }
}
