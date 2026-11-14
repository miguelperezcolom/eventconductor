package com.example.series;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Chapter 8 — a TIMER on a moment computed from the booking, and a WAIT_FOR_MESSAGE with a deadline. */
@EventConductorTest(properties = SeriesTests.NO_CRON)
class BalanceReminderTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private static void stubs(WorkflowTestRuntime runtime) {
        runtime.whenStep("send-payment-link").thenComplete(Map.of("paymentLink", "https://pay.example.com/1"));
        runtime.whenStep("confirm-guarantee").thenComplete(Map.of("guaranteed", true));
        runtime.whenStep("release-room").thenComplete();
    }

    @Test
    void thePaymentLinkGoesOutThreeDaysBeforeCheckInAtNineHotelTime(WorkflowTestRuntime runtime) {
        stubs(runtime);
        var checkIn = runtime.now().toLocalDate().plusDays(10);
        var booking = runtime.start("balance-reminder", "BALANCE-T1", Map.of("guest", "Carol", "checkIn", checkIn.toString()));
        assertThat(booking).isWaitingAt("three-days-before");

        runtime.advanceTime(Duration.ofDays(8));

        var sent = runtime.invocationsOf("send-payment-link").getLast().at();   // engine time, in the engine's zone
        var expected = checkIn.minusDays(3).atTime(LocalTime.of(9, 0)).atZone(MADRID)
                .withZoneSameInstant(io.mateu.workflow.time.EngineClock.zone()).toLocalDateTime();
        assertThat(sent).isEqualTo(expected);
        assertThat(booking).isWaitingAt("wait-payment");

        runtime.sendMessage("payment-received", "BALANCE-T1", Map.of("paidAmount", "360"));
        assertThat(booking).isCompleted().hasPassedSteps("confirm-guarantee").hasNotPassedSteps("release-room")
                .hasVariable("paidAmount", "360");
    }

    @Test
    void noPaymentByNoonTheDayBeforeReleasesTheRoom(WorkflowTestRuntime runtime) {
        stubs(runtime);
        var checkIn = runtime.now().toLocalDate().plusDays(10);
        var booking = runtime.start("balance-reminder", "BALANCE-T2", Map.of("guest", "Dan", "checkIn", checkIn.toString()));

        runtime.advanceTime(Duration.ofDays(9).plusHours(23));               // past the day before, 12:00

        assertThat(booking).isCompleted().hasPassedSteps("release-room").hasNotPassedSteps("confirm-guarantee");
    }

    @Test
    void aLateBookingIsPastThreeDaysBeforeSoTheLinkGoesOutAtOnce(WorkflowTestRuntime runtime) {
        stubs(runtime);
        var checkIn = runtime.now().toLocalDate().plusDays(2);
        var booking = runtime.start("balance-reminder", "BALANCE-T3", Map.of("guest", "Ivan", "checkIn", checkIn.toString()));

        runtime.advanceTime(Duration.ofSeconds(1));
        assertThat(booking).isWaitingAt("wait-payment");
        runtime.sendMessage("payment-received", "BALANCE-T3", Map.of("paidAmount", "480"));
        assertThat(booking).isCompleted().hasPassedSteps("send-payment-link", "confirm-guarantee");
    }
}
