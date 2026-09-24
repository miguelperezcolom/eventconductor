package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-TIME-02. A step that times out and routes to its {@code onTimeoutStepId} is handled, not a
 * failure — so a compensable step completed earlier must not be rolled back. Before the fix the
 * rollback ran <b>and</b> the flow carried on down the on-timeout route: the refund ran, the
 * shipment too, and the process ended COMPENSATED with its END cancelled.
 */
class TimeoutRouteSagaE2eTest extends AbstractE2eTest {

    @Test
    void aRoutedTimeoutCarriesOnAndCompensatesNothing() {
        worker.on("charge", TestWorker.succeed());
        worker.on("review", TestWorker.deferForever()); // nobody reviews: times out after 500 ms
        worker.on("ship", TestWorker.succeed());
        worker.on("refund", TestWorker.succeed());

        createProcess("timeout-route-saga", "trs-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("trs-1", "review").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
            assertThat(process("trs-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });
        assertThat(step("trs-1", "ship").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step("trs-1", "end").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(worker.invocationsOf("refund"))
                .as("a routed timeout is not a failure: nothing is compensated")
                .isZero();
    }
}
