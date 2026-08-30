package io.mateu.workflow.application.out.analytics;

import java.util.Arrays;

/**
 * A mergeable, fixed-bucket duration histogram — the one piece that lets the analytics read model
 * answer a p95 without keeping every sample.
 *
 * <p>The exact aggregate computes p95 as {@code percentile_disc(0.95)}: it needs all the durations
 * in the window sorted. A pre-aggregated read model cannot keep them, and a percentile is not
 * additive — you cannot sum two windows' p95s the way you sum their counts. A histogram is the
 * standard way out: counts per bucket <em>are</em> additive, so a day's buckets merge into a
 * window's by adding them, and the percentile is read back off the merged buckets. That is the
 * whole reason durations are stored as buckets rather than as a running average.
 *
 * <p>The bucket boundaries are exponential — four per octave from one microsecond up — so the
 * relative width of every bucket is the same (~19%), which is what bounds the relative error of the
 * estimate rather than its absolute size: a step that takes four milliseconds and one that takes
 * four hours are each placed to within a fifth of themselves. Below the first boundary and above the
 * last there is a catch-all bucket at each end; a duration there is estimated at the boundary, which
 * is the most honest thing to say about a sample outside the resolved range.
 *
 * <p>Serialised as the plain comma-separated bucket counts, because the projector reads a row,
 * merges a batch into it and writes it back, and a text column of longs is portable across the
 * Postgres the engine runs on and the H2 its tests boot — neither of which is asked to understand
 * the shape, only to store it.
 */
public final class DurationHistogram {

    /**
     * Upper bounds, in nanoseconds, of every non-overflow bucket: four steps per octave (each
     * {@code 2^(1/4)} ≈ 1.19× the last) from 1µs until past a day. Bucket {@code i} counts durations
     * in {@code [BOUNDS[i-1], BOUNDS[i])}; bucket 0 is everything below {@code BOUNDS[0]} and the
     * final bucket everything at or above the last bound.
     */
    static final long[] BOUNDS = buildBounds();

    /** One more than the bounds: an underflow bucket at the bottom and an overflow at the top. */
    static final int BUCKETS = BOUNDS.length + 1;

    private final long[] counts;

    private DurationHistogram(long[] counts) {
        this.counts = counts;
    }

    public static DurationHistogram empty() {
        return new DurationHistogram(new long[BUCKETS]);
    }

    /** Parses the comma-separated counts a rollup row stores; a null or blank cell is an empty one. */
    public static DurationHistogram parse(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return empty();
        }
        var parts = serialized.split(",");
        var counts = new long[BUCKETS];
        // Tolerant of a stored histogram from a different bucketing: copy what lines up, ignore the
        // rest. A read model can always be rebuilt from the raw tables, so a boundary change is a
        // backfill, never a parse failure.
        for (int i = 0; i < parts.length && i < BUCKETS; i++) {
            var cell = parts[i].trim();
            if (!cell.isEmpty()) {
                counts[i] = Long.parseLong(cell);
            }
        }
        return new DurationHistogram(counts);
    }

    private static long[] buildBounds() {
        var bounds = new java.util.ArrayList<Long>();
        double value = 1_000d; // 1µs
        double factor = Math.pow(2d, 0.25d);
        double dayNanos = 86_400d * 1_000_000_000d;
        while (value < dayNanos * 2) {
            bounds.add((long) value);
            value *= factor;
        }
        return bounds.stream().mapToLong(Long::longValue).toArray();
    }

    /** Places one duration. Negatives — a clock that went backwards — land in the bottom bucket. */
    public void add(long nanos) {
        counts[bucketOf(nanos)]++;
    }

    /** Adds another histogram into this one, bucket for bucket. The additivity the p95 rests on. */
    public void mergeIn(DurationHistogram other) {
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] += other.counts[i];
        }
    }

    static int bucketOf(long nanos) {
        if (nanos < BOUNDS[0]) {
            return 0;
        }
        // BOUNDS[i] is the upper bound of bucket i+1's predecessor; find the first bound the value
        // is below. binarySearch gives the insertion point, which is exactly that index.
        int idx = Arrays.binarySearch(BOUNDS, nanos);
        int pos = idx >= 0 ? idx + 1 : -(idx + 1);
        return pos + 1 > BUCKETS - 1 ? BUCKETS - 1 : pos + 1;
    }

    public long total() {
        long sum = 0;
        for (long c : counts) {
            sum += c;
        }
        return sum;
    }

    /**
     * The nearest-rank {@code q}-quantile, estimated off the buckets: walk the cumulative count to
     * the bucket the rank falls in and return that bucket's upper bound. Returns null when empty.
     *
     * <p>Deliberately the bucket's upper bound rather than a linear interpolation inside it: the
     * exact aggregate this stands in for is {@code percentile_disc}, which returns an actual sample
     * at or past the rank, never a value between two. Reporting the upper bound keeps the estimate
     * on the same side — it never understates a p95, which for a latency figure is the error you can
     * live with.
     */
    public Long quantileNanos(double q) {
        long total = total();
        if (total == 0) {
            return null;
        }
        long rank = (long) Math.ceil(q * total);
        long cumulative = 0;
        for (int i = 0; i < BUCKETS; i++) {
            cumulative += counts[i];
            if (cumulative >= rank) {
                return upperBoundOf(i);
            }
        }
        return upperBoundOf(BUCKETS - 1);
    }

    private static long upperBoundOf(int bucket) {
        if (bucket == 0) {
            return BOUNDS[0];
        }
        if (bucket >= BUCKETS - 1) {
            return BOUNDS[BOUNDS.length - 1];
        }
        return BOUNDS[bucket - 1];
    }

    /** The comma-separated counts a rollup row stores. */
    public String serialize() {
        var sb = new StringBuilder();
        for (int i = 0; i < BUCKETS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts[i]);
        }
        return sb.toString();
    }

    long[] countsCopy() {
        return counts.clone();
    }
}
