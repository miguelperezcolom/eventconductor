package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** E2E-TIMER-01/02/03 — TIMER steps durably pause the process until a duration or a date. */
class TimerE2eTest extends AbstractE2eTest {

    /** E2E-TIMER-01 — a TIMER step with an ISO 8601 duration pauses, then the flow resumes. */
    @Test
    void timerStepPausesThenFlowResumes() {
        worker.on("after", TestWorker.succeed());

        createProcess("timer", "tm-1");

        // While the timer runs nothing is dispatched: the step waits PENDING with no worker involved.
        assertThat(step("tm-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("after")).isZero();

        // The timer scheduler (200ms scan, PT0.5S duration) completes the step and the flow resumes.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(step("tm-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
            assertThat(process("tm-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        });

        assertThat(worker.invocationsOf("after")).isEqualTo(1);
        assertThat(worker.received().stream().map(TaskExecutionRequested::stepId))
                .doesNotContain("wait");
    }

    /** E2E-TIMER-02 — a TIMER step fires at an absolute date-time taken from a process variable. */
    @Test
    void timerFiresAtDateTakenFromProcessVariable() {
        var resumeAt = LocalDateTime.now().plus(600, ChronoUnit.MILLIS);

        createProcess("timer-until", "tm-2", new Variable("resumeAt", resumeAt.toString()));

        assertThat(step("tm-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("tm-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED));

        assertThat(LocalDateTime.now()).isAfter(resumeAt);
    }

    /** E2E-TIMER-03 — a TIMER referencing a missing date variable fails visibly, never freezing. */
    @Test
    void timerWithMissingDateVariableFailsVisibly() {
        createProcess("timer-until", "tm-3");

        assertThat(step("tm-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("tm-3").getStatus()).isEqualTo(ProcessStatus.ERROR);
    }
}
