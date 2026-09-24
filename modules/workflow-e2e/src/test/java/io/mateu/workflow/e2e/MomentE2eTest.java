package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-MOMENT-01..05 — moments from the process's data: a TIMER {@code until} ("3 days before
 * check-in, in the hotel's zone"), its {@code ifPast} policy, and a {@code deadline} on a waiting
 * step. The dates are made relative to now so the moments fall within a second.
 */
class MomentE2eTest extends AbstractE2eTest {

    /** A check-in so that "3 days before" is {@code fromNow} away, written as a local date-time in {@code zone}. */
    private static String checkInWhere(Duration fromNow, ZoneId zone, long daysAhead) {
        return ZonedDateTime.now(zone).plusDays(daysAhead).plus(fromNow).toLocalDateTime().toString();
    }

    /** E2E-MOMENT-01 — the timer fires three days before check-in, read in the hotel's zone. */
    @Test
    void aTimerFiresThreeDaysBeforeCheckInInTheHotelsZone() {
        worker.on("charge", TestWorker.succeed());
        // Tokyo, so a zone mix-up would be off by hours, not milliseconds.
        var tokyo = ZoneId.of("Asia/Tokyo");
        var firesAt = Instant.now().plusMillis(800);

        createProcess("timer-moment", "mo-1",
                new Variable("checkinAt", checkInWhere(Duration.ofMillis(800), tokyo, 3)),
                new Variable("hotelZone", tokyo.getId()));

        assertThat(step("mo-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(logsOf("mo-1")).anyMatch(line -> line.contains("Timer armed") && line.contains("Asia/Tokyo"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("mo-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(Instant.now()).isAfter(firesAt);
        assertThat(worker.invocationsOf("charge")).isEqualTo(1);
    }

    /** E2E-MOMENT-02 — a moment already past fires at once by default (a late booking is charged now). */
    @Test
    void aMomentAlreadyPastFiresAtOnceByDefault() {
        worker.on("charge", TestWorker.succeed());

        createProcess("timer-moment", "mo-2",
                new Variable("checkinAt", checkInWhere(Duration.ZERO, ZoneId.of("UTC"), 1)),
                new Variable("hotelZone", "UTC"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("mo-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("mo-2", "wait").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    /** E2E-MOMENT-03 — {@code ifPast: timeout} goes down the on-timeout route, and only there. */
    @Test
    void ifPastTimeoutTakesTheOnTimeoutRoute() {
        worker.on("charge-now", TestWorker.succeed());
        worker.on("charge-later", TestWorker.succeed());

        createProcess("timer-moment-past", "mo-3",
                new Variable("checkinAt", checkInWhere(Duration.ZERO, ZoneId.systemDefault(), 1)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("mo-3").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(step("mo-3", "wait").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(worker.invocationsOf("charge-now")).isEqualTo(1);
        assertThat(worker.invocationsOf("charge-later")).isZero();
        assertThat(errorsOf("mo-3")).anyMatch(line -> line.contains("had already passed"));
    }

    /** E2E-MOMENT-04 — {@code ifPast: timeout} with no route fails the process. */
    @Test
    void ifPastTimeoutWithoutARouteFailsTheProcess() {
        createProcess("timer-moment-strict", "mo-4",
                new Variable("checkinAt", checkInWhere(Duration.ZERO, ZoneId.systemDefault(), 1)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("mo-4").getStatus()).isEqualTo(ProcessStatus.ERROR));
        assertThat(step("mo-4", "wait").getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
    }

    /**
     * E2E-MOMENT-05 — the payment is due the day before check-in: at the deadline the wait times out
     * down its route, and its retries are not spent — a deadline is a business moment, not a budget.
     */
    @Test
    void aDeadlineTimesTheWaitOutAndRetriesDoNotExtendIt() {
        worker.on("cancel", TestWorker.succeed());
        worker.on("confirm", TestWorker.succeed());

        createProcess("deadline-message", "mo-5",
                new Variable("checkinAt", checkInWhere(Duration.ofMillis(700), ZoneId.systemDefault(), 1)));

        assertThat(step("mo-5", "wait-payment").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("mo-5").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        var wait = step("mo-5", "wait-payment");
        assertThat(wait.getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(wait.getAttemptCount()).as("no retry past a deadline").isZero();
        assertThat(worker.invocationsOf("cancel")).isEqualTo(1);
        assertThat(worker.invocationsOf("confirm")).isZero();
        assertThat(errorsOf("mo-5")).anyMatch(line -> line.contains("reached its deadline"));
    }

    /**
     * E2E-MOMENT-06 — the guest moves the check-in while the timer waits: the timer follows the
     * process's data. Booked ten days out (timer due in a week), then moved to three days out —
     * the timer is now due at once and the charge runs.
     */
    @Test
    void aTimerFollowsACheckInThatMoves() {
        worker.on("charge", TestWorker.succeed());
        var zone = ZoneId.systemDefault();

        createProcess("timer-moment-moved", "mo-6", new Variable("checkinAt", checkInWhere(Duration.ZERO, zone, 10)));

        var armed = step("mo-6", "wait");
        assertThat(armed.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(armed.getDeadlineAt()).isAfter(java.time.LocalDateTime.now().plusDays(6));

        processUpstreamEventUseCase.handle(new io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand(
                new io.mateu.workflow.dtos.events.integration.MessageReceived("booking-modified", "mo-6",
                        java.util.List.of(new Variable("checkinAt", checkInWhere(Duration.ofMillis(600), zone, 3))))));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("mo-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        assertThat(worker.invocationsOf("charge")).isEqualTo(1);
        assertThat(logsOf("mo-6")).anyMatch(line -> line.contains("moved with the process's data"));
    }
}
