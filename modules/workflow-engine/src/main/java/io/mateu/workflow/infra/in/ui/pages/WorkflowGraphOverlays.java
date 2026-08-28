package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.domain.aggregates.StepExecution;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-step monitoring overlay the {@code eventconductor-workflow-graph} component reads
 * (a map {@code stepId -> {count, heat[]}}) from a pre-filtered set of live (PENDING/RUNNING) step
 * executions. Keeping this here, taking the live list as input, lets both the whole-definition view
 * and the per-version view feed a different source (all processes vs. one version's processes) while
 * sharing one implementation.
 */
public final class WorkflowGraphOverlays {

    private WorkflowGraphOverlays() {}

    /** Longest window the heatmap slider can reach, in days. Tasks older than this fold into the
     *  last bucket, so they still show at the widest setting without unbounding the array. */
    public static final int HEAT_WINDOW_DAYS = 90;

    /** Live process count per step id, from a pre-filtered set of live step executions. */
    public static Map<String, Integer> countsByStep(List<StepExecution> live) {
        var counts = new HashMap<String, Integer>();
        for (var se : live) {
            counts.merge(se.getStepId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Per-step histogram of the live step executions bucketed by how many days ago each one started
     * ({@code heat[0]} = started today). Summing a step's buckets over the full window reproduces its
     * live count. Keyed by step id; steps with no live task are absent.
     */
    public static Map<String, int[]> heatByStep(List<StepExecution> live) {
        var now = LocalDateTime.now();
        var heat = new HashMap<String, int[]>();
        for (var se : live) {
            heat.computeIfAbsent(se.getStepId(), k -> new int[HEAT_WINDOW_DAYS])[bucketOf(se.getStartedAt(), now)]++;
        }
        return heat;
    }

    /**
     * The overlay map ready to serialize as the graph's {@code overlay} attribute: {@code stepId ->
     * {count, heat[]}}. Empty when there are no live step executions.
     */
    public static Map<String, Object> overlay(List<StepExecution> live) {
        return overlay(live, Map.of());
    }

    /**
     * The overlay, with the processes that are stopped at a step as well as the ones live on it.
     *
     * <p>The two are kept apart rather than summed. A live step is a worker owing an answer and a
     * stopped one is a process that is not going anywhere, and the operator's next move differs:
     * one is waiting, the other needs the definition looking at. Summing them would put a number on
     * the node that reads as throughput when half of it is a stall.
     *
     * <p>A step can carry both — several processes live on it and others stopped after it.
     */
    public static Map<String, Object> overlay(List<StepExecution> live, Map<String, Integer> stopped) {
        var counts = countsByStep(live);
        if (counts.isEmpty() && stopped.isEmpty()) {
            return Map.of();
        }
        var heat = heatByStep(live);
        var overlay = new HashMap<String, Object>();
        var steps = new java.util.LinkedHashSet<String>(counts.keySet());
        steps.addAll(stopped.keySet());
        for (var stepId : steps) {
            var entry = new HashMap<String, Object>();
            var liveCount = counts.getOrDefault(stepId, 0);
            if (liveCount > 0) {
                entry.put("count", liveCount);
            }
            var stoppedCount = stopped.getOrDefault(stepId, 0);
            if (stoppedCount > 0) {
                entry.put("stopped", stoppedCount);
            }
            // The histogram is built from live steps' start times, so a step with only stopped
            // processes has no heat of its own — an empty array rather than an absent key, so the
            // viewer's slider has something to sum either way.
            entry.put("heat", heat.getOrDefault(stepId, new int[HEAT_WINDOW_DAYS]));
            overlay.put(stepId, entry);
        }
        return overlay;
    }

    /** Day bucket for a task's start time: 0 = today, clamped into [0, HEAT_WINDOW_DAYS - 1]. A null
     *  start (not yet stamped) counts as today. */
    private static int bucketOf(LocalDateTime startedAt, LocalDateTime now) {
        if (startedAt == null) return 0;
        long daysAgo = Duration.between(startedAt, now).toDays();
        if (daysAgo < 0) return 0;
        return (int) Math.min(daysAgo, HEAT_WINDOW_DAYS - 1);
    }
}
