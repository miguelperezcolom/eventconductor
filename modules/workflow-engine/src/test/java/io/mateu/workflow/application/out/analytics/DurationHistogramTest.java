package io.mateu.workflow.application.out.analytics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

class DurationHistogramTest {

    @Test
    void estimatesP95NeverBelowExactAndWithinABucket() {
        var histogram = DurationHistogram.empty();
        // 1..100 milliseconds, one each: the exact nearest-rank p95 is the 95th value, 95ms.
        long exactP95 = 95_000_000L;
        for (int ms = 1; ms <= 100; ms++) {
            histogram.add(ms * 1_000_000L);
        }
        var estimate = histogram.quantileNanos(0.95);
        assertThat(estimate).isNotNull();
        assertThat(estimate).isGreaterThanOrEqualTo(exactP95);
        assertThat(estimate).isLessThanOrEqualTo((long) (exactP95 * 1.20));
    }

    @Test
    void mergingHistogramsIsAddingTheirBuckets() {
        var a = DurationHistogram.empty();
        var b = DurationHistogram.empty();
        for (int i = 0; i < 50; i++) {
            a.add(10_000_000L);
        }
        for (int i = 0; i < 50; i++) {
            b.add(2_000_000_000L);
        }
        a.mergeIn(b);
        assertThat(a.total()).isEqualTo(100);
        // Half at 10ms, half at 2s: the p95 sits up in the 2s cohort.
        assertThat(a.quantileNanos(0.95)).isGreaterThanOrEqualTo(2_000_000_000L);
    }

    @Test
    void serializeAndParseRoundTrips() {
        var histogram = DurationHistogram.empty();
        for (int i = 0; i < 1000; i++) {
            histogram.add(ThreadLocalRandom.current().nextLong(1, 5_000_000_000L));
        }
        var parsed = DurationHistogram.parse(histogram.serialize());
        assertThat(parsed.total()).isEqualTo(histogram.total());
        assertThat(parsed.quantileNanos(0.95)).isEqualTo(histogram.quantileNanos(0.95));
        assertThat(parsed.quantileNanos(0.50)).isEqualTo(histogram.quantileNanos(0.50));
    }

    @Test
    void emptyHistogramHasNoQuantile() {
        assertThat(DurationHistogram.empty().quantileNanos(0.95)).isNull();
    }
}
