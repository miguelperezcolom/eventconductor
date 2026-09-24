package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Period;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MomentRulesTest {

    @Test
    void offsetsSplitIntoCalendarAndClockParts() {
        assertThat(MomentRules.parseOffset("-P3D")).isEqualTo(new MomentRules.Offset(Period.ofDays(-3), Duration.ZERO));
        assertThat(MomentRules.parseOffset("P1DT6H")).isEqualTo(new MomentRules.Offset(Period.ofDays(1), Duration.ofHours(6)));
        assertThat(MomentRules.parseOffset("-PT30M")).isEqualTo(new MomentRules.Offset(Period.ZERO, Duration.ofMinutes(-30)));
        assertThat(MomentRules.parseOffset("P2W")).isEqualTo(new MomentRules.Offset(Period.ofDays(14), Duration.ZERO));
        assertThat(MomentRules.parseOffset("+P1M")).isEqualTo(new MomentRules.Offset(Period.ofMonths(1), Duration.ZERO));
        assertThat(MomentRules.parseOffset("PT1.5S")).isEqualTo(new MomentRules.Offset(Period.ZERO, Duration.ofMillis(1500)));
        assertThat(MomentRules.parseOffset(null)).isEqualTo(MomentRules.Offset.NONE);
    }

    @Test
    void notAnOffsetIsRefused() {
        for (var bad : new String[]{"3 days", "P", "PT", "-P3X", "P3DT", "3D"}) {
            assertThatThrownBy(() -> MomentRules.parseOffset(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aWellFormedMomentHasNoProblems() {
        assertThat(MomentRules.problems(Map.of("date", "${checkinDate}", "offset", "-P3D", "at", "09:00",
                "zone", "${hotelZone}", "ifPast", "timeout"), "until", true)).isEmpty();
        assertThat(MomentRules.problems("${checkinDate}", "until", true)).isEmpty();
        assertThat(MomentRules.problems(Map.of("date", "2026-08-01", "zone", "Europe/Madrid"), "deadline", false)).isEmpty();
        assertThat(MomentRules.problems(null, "until", true)).isEmpty();
    }

    @Test
    void everyFieldIsChecked() {
        assertThat(MomentRules.problems(Map.of("offset", "-P3D"), "until", true))
                .containsExactly("until must define a date.");
        assertThat(MomentRules.problems(Map.of("date", "${x", "offset", "3 days", "at", "9h",
                "zone", "Mars/Olympus", "ifPast", "skip", "when", "now"), "until", true))
                .hasSize(6)
                .anyMatch(p -> p.contains("date"))
                .anyMatch(p -> p.contains("offset"))
                .anyMatch(p -> p.contains("at:"))
                .anyMatch(p -> p.contains("Mars/Olympus"))
                .anyMatch(p -> p.contains("ifPast"))
                .anyMatch(p -> p.contains("'when'"));
    }

    @Test
    void aDeadlineHasNoPastPolicy() {
        assertThat(MomentRules.problems(Map.of("date", "${d}", "ifPast", "fire"), "deadline", false))
                .containsExactly("deadline has an unknown field 'ifPast'.");
        assertThat(MomentRules.problems(42, "deadline", false)).hasSize(1);
    }
}
