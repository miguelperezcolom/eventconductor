package com.example.series;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chapters 8 and 9 — the doors nobody calls by hand: the nightly schedule of cron.yaml and the event trigger of
 * events.yaml. The schedule is on in this test (and only in this one, which therefore has a context of its own).
 */
@EventConductorTest
class ScheduleAndTriggerTest {

    @Test
    void theNightlyReportStartsAtThreeInTheMorningMadridTime(WorkflowTestRuntime runtime) {
        runtime.whenStep("count-occupancy").thenComplete(Map.of("occupiedRooms", 37, "totalRooms", 52));
        var before = runtime.processes("nightly-occupancy").size();

        var nextThree = runtime.now().atZone(io.mateu.workflow.time.EngineClock.zone())
                .withZoneSameInstant(ZoneId.of("Europe/Madrid")).toLocalDate().plusDays(1).atTime(LocalTime.of(3, 0))
                .atZone(ZoneId.of("Europe/Madrid")).withZoneSameInstant(io.mateu.workflow.time.EngineClock.zone())
                .toLocalDateTime();
        runtime.advanceTimeTo(nextThree.plusMinutes(1));

        var reports = runtime.processes("nightly-occupancy");
        assertThat(reports.size()).isGreaterThan(before);
        var last = reports.getLast();
        assertThat(last).isCompleted().hasVariable("hotel", "Workshop Hotel").hasVariable("occupiedRooms", "37");
    }

    @Test
    void aBookingCreatedEventStartsTheOnboarding(WorkflowTestRuntime runtime) {
        runtime.whenStep("register-guest").thenComplete(Map.of("guestId", "G-1"));

        var result = runtime.publishEvent("bookings", "com.example.booking.created",
                Map.of("bookingId", "CM-T1", "guest", "Alice", "checkIn", "2026-12-20"));
        assertThat(result.receipts()).isNotEmpty();

        var onboarding = runtime.process("booking-onboarding-CM-T1");
        assertThat(onboarding).isCompleted().hasVariable("bookingId", "CM-T1").hasVariable("guest", "Alice")
                .hasPublishedEvent("com.example.guest.welcomed");
        assertThat(runtime.publishedEvents(onboarding).getFirst().data()).contains("\"guestId\":\"G-1\"");
    }
}
