package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The materialised deadline is derived state, and derived state that is stored can drift from
 * what it derives from. These pin the invariant: it is armed when the clock starts and
 * recomputed by every path that moves the clock, so no sequence of calls can leave a step
 * waiting on a moment that no longer matches its {@code startedAt}.
 */
class StepExecutionDeadlineTest {

    private Step timerStep(long durationMillis, String untilVariable) {
        return new Step("s1", "wd-1", StepType.TIMER, "Wait", null, null, null, null, false, null, null, null, null, null, durationMillis, untilVariable, null, null, null, 0, 0, false, null, 0, null);
    }

    private Step actionStep(long timeoutMillis) {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, timeoutMillis, 0, false, null, 0, null);
    }

    private StepExecution created(Step step) {
        return StepExecution.create(step, "p-1", 0);
    }

    private Process process(Variable... variables) {
        return Process.builder().id("p-1").variables(List.of(variables)).build();
    }

    @Test
    void armsTheTimerDueMomentOnStart() {
        var stepExecution = created(timerStep(60_000, null)).start(process());

        assertThat(stepExecution.getDeadlineAt())
                .isEqualTo(stepExecution.getStartedAt().plusSeconds(60));
    }

    @Test
    void armsTheTimeoutDeadlineOnStart() {
        var stepExecution = created(actionStep(30_000)).start(process());

        assertThat(stepExecution.getDeadlineAt())
                .isEqualTo(stepExecution.getStartedAt().plusSeconds(30));
    }

    @Test
    void armsTheDateVariableTimerOnStart() {
        var dueAt = LocalDateTime.now().plusDays(21).withNano(0);
        var stepExecution = created(timerStep(0, "checkInAt"))
                .start(process(new Variable("checkInAt", dueAt.toString())));

        assertThat(stepExecution.getDeadlineAt()).isEqualTo(dueAt);
    }

    @Test
    void leavesNoDeadlineForAStepThatNeedsNoAttention() {
        var stepExecution = created(actionStep(0)).start(process());

        assertThat(stepExecution.getDeadlineAt()).isNull();
    }

    @Test
    void leavesNoDeadlineForAMisconfiguredTimerAndErrorsIt() {
        // The date variable the timer expects is not on the process: the step fails at start,
        // so there must be nothing left armed for the scheduler to fire.
        var stepExecution = created(timerStep(0, "missing")).start(process());

        assertThat(stepExecution.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(stepExecution.getDeadlineAt()).isNull();
    }

    @Test
    void movingTheClockMovesTheDeadlineWithIt() {
        // Pause/resume shifts startedAt by the pause duration. Were the deadline to stay put,
        // a timer paused past its due moment would fire the instant the process resumed.
        var started = created(timerStep(60_000, null)).start(process());

        var shifted = started.withStartedAt(started.getStartedAt().plus(Duration.ofHours(2)));

        assertThat(shifted.getDeadlineAt()).isEqualTo(started.getDeadlineAt().plusHours(2));
    }

    @Test
    void movingTheClockOfATimeoutStepMovesItsDeadlineToo() {
        var started = created(actionStep(30_000)).start(process());

        var shifted = started.withStartedAt(started.getStartedAt().plus(Duration.ofMinutes(5)));

        assertThat(shifted.getDeadlineAt()).isEqualTo(started.getDeadlineAt().plusMinutes(5));
    }

    @Test
    void movingTheClockKeepsTheRestOfTheStepIntact() {
        var started = created(actionStep(30_000)).start(process(new Variable("a", "1")));

        var shifted = started.withStartedAt(started.getStartedAt().plusMinutes(1));

        assertThat(shifted.id()).isEqualTo(started.id());
        assertThat(shifted.getProcessId()).isEqualTo("p-1");
        assertThat(shifted.getStatus()).isEqualTo(started.getStatus());
        assertThat(shifted.getStepJson()).isEqualTo(started.getStepJson());
        assertThat(shifted.getVariables()).isEqualTo(started.getVariables());
    }

    @Test
    void retryArmsTheBackoffDeadlineThenReleaseDisarmsIt() {
        var started = created(actionStep(30_000)).start(process());
        assertThat(started.getDeadlineAt()).isNotNull();

        // The retry now parks the step for a backoff: the deadline is the moment it may run again,
        // and the previous attempt's timeout deadline is replaced by it, not left to survive.
        started.scheduleRetry(java.time.Duration.ofMillis(5000));
        assertThat(started.getStatus()).isEqualTo(io.mateu.workflow.domain.aggregates.StepExecutionStatus.AWAITING_RETRY);
        assertThat(started.getDeadlineAt()).isNotNull();

        // Releasing it for re-dispatch disarms the backoff deadline; start() arms a fresh one.
        started.releaseForRetry();
        assertThat(started.getDeadlineAt()).isNull();
    }

    @Test
    void aStepThatNeverStartedHasNoDeadline() {
        assertThat(created(timerStep(60_000, null)).getDeadlineAt()).isNull();
    }

    @Test
    void stepDeadlineAtIsNullWhenNoTimeoutIsConfigured() {
        assertThat(actionStep(0).deadlineAt(LocalDateTime.now(), List.of())).isNull();
    }

    @Test
    void deserialisedStepStillComputesItsDeadline() {
        // The deadline is recomputed from stepJson, so it has to survive the round trip.
        var step = JsonSerializer.pojoFromJson(
                JsonSerializer.toJson(timerStep(45_000, null)), Step.class);
        var startedAt = LocalDateTime.now();

        assertThat(step.deadlineAt(startedAt, List.of())).isEqualTo(startedAt.plusSeconds(45));
    }
}
