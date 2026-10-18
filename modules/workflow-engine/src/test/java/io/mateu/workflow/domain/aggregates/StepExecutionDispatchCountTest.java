package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every dispatch of a step execution gets its own number, carried on the task, and every way back to
 * CREATED moves past it — so a late reply from an abandoned attempt can be told from the current one.
 */
class StepExecutionDispatchCountTest {

    final Process process = Process.builder().id("p-1").variables(List.of()).build();

    StepExecution action() {
        var step = new Step("charge", "wd-1", StepType.ACTION, "Charge", null, null, null, null, false, "topic",
                null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        return StepExecution.create(step, "p-1", 1);
    }

    private static Integer attemptOnTheTask(StepExecution stepExecution) {
        return stepExecution.popEvents().stream()
                .filter(TaskExecutionRequested.class::isInstance)
                .map(event -> ((TaskExecutionRequested) event).attempt())
                .findFirst().orElseThrow();
    }

    @Test
    void eachStartIsANewDispatchAndTheTaskCarriesIt() {
        var stepExecution = action();
        assertThat(stepExecution.getDispatchCount()).isZero();

        stepExecution.start(process);
        assertThat(attemptOnTheTask(stepExecution)).isEqualTo(1);

        // Restarted from the top while it was still at a worker: the reset alone already moves past it.
        stepExecution.resetForRerun();
        assertThat(stepExecution.getDispatchCount()).isEqualTo(2);
        stepExecution.start(process);
        assertThat(attemptOnTheTask(stepExecution)).isEqualTo(3);
    }

    @Test
    void everyWayBackToCreatedMovesPastTheAttemptThatWasRunning() {
        var stepExecution = action();
        stepExecution.start(process);
        var first = stepExecution.getDispatchCount();

        // Timed out and retried by an operator (RetryStepExecutionUseCase / RetryProcessUseCase).
        stepExecution.updateStatus(StepExecutionStatus.TIMEOUT);
        stepExecution.updateStatus(StepExecutionStatus.CREATED);
        assertThat(stepExecution.getDispatchCount()).isGreaterThan(first);

        // Automatic retry after a backoff.
        var second = stepExecution.getDispatchCount();
        stepExecution.scheduleRetry(Duration.ZERO);
        stepExecution.releaseForRetry();
        assertThat(stepExecution.getDispatchCount()).isGreaterThan(second);
    }
}
