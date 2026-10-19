package io.mateu.workflowbench.suite;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The arithmetic the published tables rest on: nearest-rank percentiles and medians across repetitions. */
class StatsTest {

    @Test
    void nearestRankPercentiles() {
        var sorted = new double[100];
        for (var i = 0; i < 100; i++) {
            sorted[i] = i + 1; // 1..100
        }
        assertThat(Stats.percentile(sorted, 50)).isEqualTo(50);
        assertThat(Stats.percentile(sorted, 95)).isEqualTo(95);
        assertThat(Stats.percentile(sorted, 99)).isEqualTo(99);
        assertThat(Stats.percentile(sorted, 100)).isEqualTo(100);
        assertThat(Stats.percentile(new double[] {7}, 99)).isEqualTo(7);
        assertThat(Stats.percentile(new double[0], 50)).isNaN();
    }

    @Test
    void medianOfOddAndEvenCountsIgnoresMissingValues() {
        assertThat(Stats.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(Stats.median(List.of(4.0, 1.0, 2.0, 3.0))).isEqualTo(2.5);
        assertThat(Stats.median(List.of(Double.NaN, 5.0))).isEqualTo(5.0);
    }

    @Test
    void summaryTakesMedianMinAndMaxOfEveryNumericLeafAcrossRepetitions() {
        var summary = Report.summarize(List.of(
                Map.of("throughputPerSec", 10.0, "latencyMs", Map.of("p50", 5.0)),
                Map.of("throughputPerSec", 30.0, "latencyMs", Map.of("p50", 7.0)),
                Map.of("throughputPerSec", 20.0, "latencyMs", Map.of("p50", 6.0))));
        assertThat(summary.get("throughputPerSec")).isEqualTo(Map.of("median", 20.0, "min", 10.0, "max", 30.0));
        assertThat(summary.get("latencyMs.p50")).isEqualTo(Map.of("median", 6.0, "min", 5.0, "max", 7.0));
    }
}
