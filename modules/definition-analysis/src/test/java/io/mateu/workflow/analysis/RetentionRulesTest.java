package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionRulesTest {

    @Test
    void acceptsIsoDurationsInDaysToSecondsAndForever() {
        assertThat(RetentionRules.parse("P30D")).contains(Duration.ofDays(30));
        assertThat(RetentionRules.parse("PT12H")).contains(Duration.ofHours(12));
        assertThat(RetentionRules.parse(" P1DT12H ")).contains(Duration.ofHours(36));
        assertThat(RetentionRules.parse("PT0.5S")).contains(Duration.ofMillis(500));
        assertThat(RetentionRules.parse("forever")).isEmpty();
        assertThat(RetentionRules.isValid("P180D")).isTrue();
    }

    @Test
    void refusesWhatIsNotAPositiveDuration() {
        for (var bad : new String[]{null, "", " ", "P", "PT", "P1DT", "P1M", "P1Y", "P2W", "30d", "p30d", "P0D",
                "PT0S", "-P1D", "P-1D", "never", "Forever"}) {
            assertThat(RetentionRules.isValid(bad)).as(bad).isFalse();
        }
        assertThatThrownBy(() -> RetentionRules.parse("P1M")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P365D");
        assertThat(RetentionRules.violations("P0D", null, "retention")).singleElement().asString()
                .startsWith("retention: ").contains("longer than zero");
    }

    @Test
    void perStatusValuesAreCheckedAndOnlyTerminalStatusesAreKeys() {
        var byStatus = new LinkedHashMap<String, String>();
        byStatus.put("completed", "P30D");
        byStatus.put("error", "forever");
        byStatus.put("compensationFailed", "P180D");
        assertThat(RetentionRules.violations(null, byStatus, "retention")).isEmpty();

        byStatus.put("running", "P1D");
        byStatus.put("cancelled", "P1M");
        assertThat(RetentionRules.violations(null, byStatus, "retention")).containsExactly(
                "retention: 'running' is not a terminal status (one of completed, cancelled, error, compensated, compensationFailed)",
                "retention: cancelled: 'P1M' is not a retention: write an ISO-8601 duration in days, hours, minutes or"
                        + " seconds (P30D, PT12H, P1DT12H — months and years are not durations: write P365D), or 'forever'");
    }

    @Test
    void anEmptyMapAndBothShapesAtOnceAreRefusedAndNothingDeclaredIsFine() {
        assertThat(RetentionRules.violations(null, Map.of(), null)).singleElement().asString()
                .contains("at least one status");
        assertThat(RetentionRules.violations("P1D", Map.of("error", "P2D"), "")).singleElement().asString()
                .contains("not both");
        assertThat(RetentionRules.violations(null, null, "retention")).isEmpty();
    }
}
