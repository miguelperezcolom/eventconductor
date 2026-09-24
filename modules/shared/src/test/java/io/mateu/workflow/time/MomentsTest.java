package io.mateu.workflow.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MomentsTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @AfterEach
    void resetZone() {
        Moments.setDefaultZone(null);
    }

    @Test
    void threeDaysBeforeCheckInAtNineHotelTime() {
        var resolved = Moments.resolve("${checkinDate}", "-P3D", "09:00", "${hotelZone}",
                Map.of("checkinDate", "2026-08-01", "hotelZone", "Europe/Madrid"));
        assertThat(resolved.zoned()).isEqualTo(ZonedDateTime.of(2026, 7, 29, 9, 0, 0, 0, MADRID));
        assertThat(resolved.engineTime()).isEqualTo(
                resolved.zoned().withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
    }

    @Test
    void calendarDaysKeepTheWallClockAcrossDst_clockHoursDoNot() {
        // Summer time in Madrid ends in the night of 2026-10-24 → 25 (03:00 → 02:00), so the day
        // before 10:00 on the 25th is 25 hours long.
        var ctx = Map.of("d", "2026-10-25T10:00");
        assertThat(Moments.resolve("${d}", "-P1D", null, "Europe/Madrid", ctx).zoned().toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));
        assertThat(Moments.resolve("${d}", "-PT24H", null, "Europe/Madrid", ctx).zoned().toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 11, 0));
    }

    @Test
    void aTimeInTheSpringGapMovesForward() {
        // 2026-03-29 02:30 does not exist in Madrid.
        var zoned = Moments.resolve("2026-03-29", null, "02:30", "Europe/Madrid", Map.of()).zoned();
        assertThat(zoned.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 3, 29, 3, 30));
    }

    @Test
    void anOffsetDateTimeKeepsItsInstant() {
        var zoned = Moments.resolve("2026-08-01T12:00:00Z", null, null, "Europe/Madrid", Map.of()).zoned();
        assertThat(zoned).isEqualTo(ZonedDateTime.of(2026, 8, 1, 14, 0, 0, 0, MADRID));
    }

    @Test
    void theDefaultZoneReadsDatesWithoutOne() {
        Moments.setDefaultZone(ZoneId.of("Asia/Tokyo"));
        var zoned = Moments.resolve("2026-08-01", null, null, null, Map.of()).zoned();
        assertThat(zoned).isEqualTo(ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneId.of("Asia/Tokyo")));
        // An empty zone variable falls back to the default rather than failing.
        assertThat(Moments.resolve("2026-08-01", null, null, "${hotelZone}", Map.of()).zoned().getZone())
                .isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void aMissingOrUnreadableDateIsAnError() {
        assertThatThrownBy(() -> Moments.resolve("${checkinDate}", null, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> Moments.resolve("${checkinDate}", null, null, null, Map.of("checkinDate", "soon")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("soon");
        assertThatThrownBy(() -> Moments.resolve("2026-08-01", null, null, "${z}", Map.of("z", "Nowhere/City")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nowhere/City");
    }
}
