package io.mateu.workflow.application.out;

import java.util.List;

/**
 * The two reductions a duration distribution needs, in the one definition both stores answer to.
 *
 * <p>It exists so the in-memory store and the equivalence test cannot disagree with each other by
 * accident, and so the nearest-rank rule is written down once. The JPA store does not call it — it
 * asks the database for the same two numbers — but it is held to producing them.
 */
public final class AnalyticsMath {

    private AnalyticsMath() {
    }

    /**
     * Sum and nearest-rank 95th percentile over measured durations, in nanoseconds.
     *
     * <p><b>Nearest rank</b>, meaning the sample sitting at {@code ceil(0.95 × n)} of the sorted
     * values — a value that was actually measured, never an interpolation between two. That is what
     * {@code percentile_disc(0.95)} returns in SQL, which is what lets the two stores agree on a
     * number rather than on a rounding.
     */
    public static AnalyticsAggregates.DurationAggregate aggregate(List<Long> nanos) {
        if (nanos == null || nanos.isEmpty()) {
            return AnalyticsAggregates.DurationAggregate.NONE;
        }
        var sorted = nanos.stream().sorted().toList();
        long total = 0;
        for (var value : sorted) {
            total += value;
        }
        var p95 = sorted.get(Math.max(0, (int) Math.ceil(0.95 * sorted.size()) - 1));
        return new AnalyticsAggregates.DurationAggregate(sorted.size(), total, p95);
    }
}
