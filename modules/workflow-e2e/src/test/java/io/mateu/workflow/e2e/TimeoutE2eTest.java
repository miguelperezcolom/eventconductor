package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** E2E-TIME-01 — verifies the memory-mode timeout scheduler actually fires. */
class TimeoutE2eTest extends AbstractE2eTest {

    @Test
    void unresponsiveStepTimesOutAndProcessFails() {
        worker.on("hang", TestWorker.deferForever()); // worker never calls back

        createProcess("timeout", "to-1");

        // The step is dispatched and left PENDING; the timeout scheduler (200ms scan,
        // 500ms step timeout) must transition it to TIMEOUT and fail the process.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("to-1", "hang").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
            assertThat(process("to-1").getStatus()).isEqualTo(ProcessStatus.ERROR);
        });
    }
}
