package io.mateu.workflowbench.suite;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Percentiles and medians, nearest-rank, over plain doubles. No interpolation, no smoothing. */
public final class Stats {

    /** Nearest-rank percentile: the smallest sample with at least {@code p}% of samples at or below it. */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        var rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(rank - 1, sorted.length - 1))];
    }

    /** p50/p95/p99/max (and the sample count) of a set of latencies in milliseconds. */
    public static Map<String, Object> latency(Collection<Double> millis) {
        var sorted = millis.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        var out = new LinkedHashMap<String, Object>();
        out.put("n", sorted.length);
        out.put("p50", round(percentile(sorted, 50)));
        out.put("p95", round(percentile(sorted, 95)));
        out.put("p99", round(percentile(sorted, 99)));
        out.put("max", round(sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1]));
        return out;
    }

    public static double median(List<Double> values) {
        var sorted = values.stream().filter(v -> !Double.isNaN(v)).mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) {
            return Double.NaN;
        }
        var mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    public static double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).filter(v -> !Double.isNaN(v)).min().orElse(Double.NaN);
    }

    public static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).filter(v -> !Double.isNaN(v)).max().orElse(Double.NaN);
    }

    /** Two decimals: the measurements are not more precise than that, and the report should not pretend. */
    public static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    static double[] sorted(double[] values) {
        var copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    private Stats() {
    }
}
