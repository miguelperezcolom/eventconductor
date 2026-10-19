package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** The guide's example: a saga with a three-day TIMER and a compensation, tested in milliseconds. */
@EventConductorTest
class BookingSagaTest {

    @Test
    void capturesThePaymentOnceTheCoolingOffPeriodIsOver(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete(Map.of("roomId", "R-12"));
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_1"));
        runtime.whenTask("capture-payment").thenComplete();

        var booking = runtime.start("booking-saga", "saga-1", Map.of("amount", 120));
        var startedAt = runtime.now();

        assertThat(booking).isRunning().isWaitingAt("cooling-off").hasVariable("chargeId", "ch_1");

        runtime.advanceTime(Duration.ofDays(2));
        assertThat(booking).isWaitingAt("cooling-off");
        assertThat(runtime.invocationsOf("capture")).isEmpty();

        runtime.advanceTime(Duration.ofDays(1));
        assertThat(booking).isCompleted().hasPassedSteps("reserve", "charge", "cooling-off", "capture");
        // It fired at its own moment, not at the end of the jump.
        assertThat(runtime.invocationsOf("capture").getFirst().at()).isEqualTo(startedAt.plusDays(3));
    }

    @Test
    void refundsAndReleasesWhenTheCaptureIsDeclined(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete(Map.of("roomId", "R-12"));
        runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_2"));
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);
        runtime.whenTask("refund").thenComplete();
        runtime.whenTask("release-room").thenComplete();

        var booking = runtime.start("booking-saga", "saga-2", Map.of("amount", 120));
        runtime.advanceTime(Duration.ofDays(3));

        assertThat(booking).isCompensated()
                .hasPassedSteps("reserve", "charge", "refund", "release")
                .hasNotPassedSteps("capture");
        assertThat(runtime.invocationsOf("refund")).singleElement()
                .satisfies(refund -> assertThat(refund.variable("chargeId")).isEqualTo("ch_2"));
    }

    @Test
    void aFailingCompensationIsReportedAsSuch(WorkflowTestRuntime runtime) {
        runtime.whenTask("reserve-room").thenComplete();
        runtime.whenTask("charge-card").thenComplete();
        runtime.whenTask("capture-payment").thenFail("CARD_EXPIRED", false);
        runtime.whenTask("refund").thenFail("REFUND_REJECTED", false);
        runtime.whenTask("release-room").thenComplete();

        var booking = runtime.start("booking-saga", "saga-3", Map.of());
        runtime.advanceTime(Duration.ofDays(4));

        assertThat(booking).isCompensationFailed();
    }
}
