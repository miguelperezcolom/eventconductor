package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which steps a rollback undid, and in which order. */
@EventConductorTest
class CompensationAssertionsTest {

    private static void captureIsDeclined(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete(Map.of("roomId", "R-12"));
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_2"));
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);
        runtime.whenTask("release-room").thenComplete();
    }

    @Test
    void aRollbackUndoesTheLatestStepFirst(WorkflowTestRuntime runtime) {
        captureIsDeclined(runtime);
        runtime.whenTask("refund").thenComplete();

        var booking = runtime.start("booking-saga", "comp-1", Map.of("amount", 120));
        runtime.advanceTime(Duration.ofDays(3));

        assertThat(booking).isCompensated()
                .hasCompensated("charge", "reserve")
                .hasCompensatedInOrder("charge", "reserve")
                .hasNotCompensated("capture");

        assertThatThrownBy(() -> assertThat(booking).hasCompensatedInOrder("reserve", "charge"))
                .hasMessageContaining("but it compensated [charge, reserve]")
                .hasMessageContaining("refund                   undoing charge");
        assertThatThrownBy(() -> assertThat(booking).hasNotCompensated("reserve"))
                .hasMessageContaining("but it compensated [reserve]");
        assertThatThrownBy(() -> assertThat(booking).hasNoCompensations())
                .hasMessageContaining("Compensations, in the order they ran");
    }

    @Test
    void aCompensationThatFailedUndidNothing(WorkflowTestRuntime runtime) {
        captureIsDeclined(runtime);
        runtime.whenTask("refund").thenFail("REFUND_REJECTED", false);

        var booking = runtime.start("booking-saga", "comp-2", Map.of());
        runtime.advanceTime(Duration.ofDays(4));

        assertThat(booking).isCompensationFailed().hasNotCompensated("charge", "reserve").hasCompensatedInOrder();
        assertThatThrownBy(() -> assertThat(booking).hasCompensated("charge"))
                .hasMessageContaining("it has not compensated [charge]")
                .hasMessageContaining("undoing charge")
                .hasMessageContaining("ERROR");
    }

    @Test
    void aRunThatCompletesCompensatesNothing(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete();
        runtime.whenTask("capture-payment").thenComplete();

        var booking = runtime.start("booking-saga", "comp-3", Map.of());
        runtime.advanceTime(Duration.ofDays(3));

        assertThat(booking).isCompleted().hasNoCompensations().hasNotCompensated("charge", "reserve");
        assertThatThrownBy(() -> assertThat(booking).hasCompensated("reserve"))
                .hasMessageContaining("Compensations: none ran.");
    }

    @Test
    void theIterationsOfAForEachAreUndoneOneByOne(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenAnswer(task -> "2".equals(task.variable("room"))
                ? TaskOutcome.fail("FULL", false)
                : TaskOutcome.complete(Map.of("reservationId", "R" + task.variable("room"))));
        runtime.whenTask("release-room").thenComplete();

        var run = runtime.start("rooms-compensate-all", "comp-4", Map.of("rooms", List.of("1", "2", "3")));

        assertThat(run).isCompensated().hasCompensated("reserve");
        assertThat(run).iterations("reserve")
                .hasSize(3)
                .hasCompleted(0, 2)
                .hasOnlyFailed(1)
                .hasFailed("2")                                   // by item
                .hasCompensated(2, 0);
        assertThat(run).iterations("reserve").iteration(0).isCompleted().hasItem("1")
                .hasOutput("reservationId", "R1").wasCompensated();
        assertThat(run).iterations("reserve").iteration(1).isFailed().wasNotCompensated();
    }
}
