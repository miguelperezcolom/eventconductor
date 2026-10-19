package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retries with backoff, timeouts and timeout routes, cron schedules: all on the test clock. */
@EventConductorTest
class TimeSkippingTest {

    @Test
    void retriesWaitOutTheirBackoffOnTheTestClock(WorkflowTestRuntime runtime) {
        runtime.whenTask("charge-card")
                .thenFail("GATEWAY_DOWN")
                .thenFail("GATEWAY_DOWN")
                .thenComplete(Map.of("chargeId", "ch_9"));

        var charge = runtime.start("flaky-charge", "retry-1", Map.of());

        // The first failure parks the step until its backoff (1 s ± 20 % by default) is over.
        assertThat(charge).isWaitingAt("charge").hasStepStatus("charge", StepExecutionStatus.AWAITING_RETRY);
        assertThat(runtime.invocationsOf("charge")).hasSize(1);

        runtime.advanceTime(Duration.ofMinutes(1));

        assertThat(charge).isCompleted().hasVariable("chargeId", "ch_9");
        var calls = runtime.invocationsOf("charge");
        assertThat(calls).hasSize(3);
        var firstBackoff = Duration.between(calls.get(0).at(), calls.get(1).at());
        var secondBackoff = Duration.between(calls.get(1).at(), calls.get(2).at());
        assertThat(firstBackoff).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
        assertThat(secondBackoff).isBetween(Duration.ofMillis(1600), Duration.ofMillis(2400));
        // One step execution, retried: the attempts are counted on it.
        assertThat(charge).stepExecutions("charge").hasSize(1);
    }

    @Test
    void aWorkerThatNeverAnswersIsTimedOut(WorkflowTestRuntime runtime) {
        runtime.whenTask("supplier").thenTimeout();

        var order = runtime.start("slow-supplier", "timeout-1", Map.of());

        runtime.advanceTime(Duration.ofMinutes(59));
        assertThat(order).isWaitingAt("order");

        runtime.advanceTime(Duration.ofMinutes(1));
        // A timeout expires strictly after its limit: at exactly one hour the step is still waiting.
        assertThat(order).isWaitingAt("order");

        runtime.advanceTime(Duration.ofSeconds(1));
        assertThat(order).isFailed().hasStepStatus("order", StepExecutionStatus.TIMEOUT);
    }

    @Test
    void aTimedOutMessageWaitTakesItsTimeoutRoute(WorkflowTestRuntime runtime) {
        runtime.whenTask("chase").thenComplete();

        var stay = runtime.start("guest-confirmation", "route-1", Map.of());
        runtime.advanceTime(Duration.ofDays(1).plusMinutes(1));

        assertThat(stay).isCompleted().hasPassedSteps("chase").hasNotPassedSteps("confirm")
                .hasStepStatus("await-guest", StepExecutionStatus.TIMEOUT);
    }

    @Test
    void aMessageBeforeTheDeadlineWins(WorkflowTestRuntime runtime) {
        runtime.whenTask("confirm").thenComplete();
        runtime.whenTask("chase").thenComplete();

        var stay = runtime.start("guest-confirmation", "route-2", Map.of());
        runtime.advanceTime(Duration.ofHours(23));
        runtime.sendMessage("guest-confirmed", "route-2", Map.of("confirmed", "yes"));

        assertThat(stay).isCompleted().hasPassedSteps("await-guest", "confirm").hasNotPassedSteps("chase")
                .hasVariable("confirmed", "yes");
        runtime.advanceTime(Duration.ofDays(2));
        assertThat(runtime.invocationsOf("chase")).isEmpty();
    }

    @Test
    void cronSchedulesFireAtEachOccurrence(WorkflowTestRuntime runtime) {
        runtime.whenTask("report").thenComplete();
        var before = runtime.processes("nightly-report").size();

        runtime.advanceTime(Duration.ofDays(3));

        var started = runtime.processes("nightly-report");
        assertThat(started).hasSize(before + 3);
        var last = started.getLast();
        assertThat(last).isCompleted().hasVariable("kind", "daily");
        assertThat(last.state().getCreated().truncatedTo(ChronoUnit.SECONDS).atZone(runtime.clock().getZone())
                .withZoneSameInstant(java.time.ZoneOffset.UTC).getHour()).isEqualTo(9);
    }

    @Test
    void theClockOnlyMovesForward(WorkflowTestRuntime runtime) {
        var now = runtime.now();
        runtime.advanceTimeTo(now.minusHours(1));
        assertThat(runtime.now()).isEqualTo(now);
        assertThatThrownBy(() -> runtime.advanceTime(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.clock().advance(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        runtime.advanceTimeTo(now.plusHours(2));
        assertThat(runtime.now()).isEqualTo(now.plusHours(2));
        assertThat(io.mateu.workflow.time.EngineClock.now()).isEqualTo(runtime.now());
    }
}
