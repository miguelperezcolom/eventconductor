package io.mateu.workflow.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class EngineClockTest {

    @AfterEach
    void restore() {
        EngineClock.reset();
    }

    @Test
    void defaultsToTheSystemClockInTheDefaultZone() {
        assertThat(EngineClock.get()).isSameAs(EngineClock.system());
        assertThat(EngineClock.zone()).isEqualTo(ZoneId.systemDefault());
        var before = LocalDateTime.now();
        var now = EngineClock.now();
        assertThat(now).isAfterOrEqualTo(before).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void readsEverythingFromTheInstalledClock() {
        var fixed = Instant.parse("2030-01-02T03:04:05Z");
        EngineClock.set(Clock.fixed(fixed, ZoneId.of("UTC")));

        assertThat(EngineClock.instant()).isEqualTo(fixed);
        assertThat(EngineClock.millis()).isEqualTo(fixed.toEpochMilli());
        assertThat(EngineClock.now()).isEqualTo(LocalDateTime.of(2030, 1, 2, 3, 4, 5));
        assertThat(EngineClock.today()).isEqualTo(LocalDate.of(2030, 1, 2));
        assertThat(EngineClock.zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void nullAndResetRestoreTheSystemClock() {
        EngineClock.set(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
        EngineClock.set(null);
        assertThat(EngineClock.get()).isSameAs(EngineClock.system());

        EngineClock.set(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
        EngineClock.reset();
        assertThat(EngineClock.get()).isSameAs(EngineClock.system());
    }
}
