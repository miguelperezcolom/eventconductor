package io.mateu.workflow.application.out.analytics;

import io.mateu.workflow.application.out.AnalyticsAggregates;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionDayCount;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionDuration;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionStatusCount;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionStepCount;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionStepDuration;
import io.mateu.workflow.application.out.AnalyticsAggregates.DurationAggregate;
import io.mateu.workflow.application.out.AnalyticsAggregates.ProcessAggregates;
import io.mateu.workflow.application.out.AnalyticsAggregates.StepAggregates;
import io.mateu.workflow.application.out.analytics.RollupModel.LiveProcessStatus;
import io.mateu.workflow.application.out.analytics.RollupModel.LiveStepStatus;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessCreatedDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessDurationDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessFinishedDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessStatusDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.StepDurationDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.StepStatusDaily;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the rollup rows a window covers — plus the live overlay of what is still in flight — back
 * into the {@link AnalyticsAggregates} the service already knows how to read. Pure: the reader hands
 * it rows, it hands back aggregates, and nothing here touches a database.
 *
 * <p>Summing the rollup is the whole point: counts add, samples and totals add,
 * {@link DurationHistogram}s merge. The overlay is added on top of the terminal counts because a
 * process that has not finished is not in the rollup yet — the rollup holds immutable facts, and a
 * running process is not one. The two are disjoint (finished versus not), so adding them
 * double-counts nothing.
 */
public final class RollupReducer {

    private RollupReducer() {
    }

    public static ProcessAggregates reduceProcesses(List<ProcessStatusDaily> statusRows,
                                                    List<LiveProcessStatus> liveStatus,
                                                    List<ProcessCreatedDaily> createdRows,
                                                    List<ProcessFinishedDaily> finishedRows,
                                                    List<ProcessDurationDaily> durationRows) {
        // Status counts: the terminal rollup, then the in-flight overlay on top.
        Map<StatusKey, long[]> counts = new LinkedHashMap<>();
        Map<StatusKey, String> names = new LinkedHashMap<>();
        for (var row : statusRows) {
            var key = new StatusKey(row.definitionId(), row.status().name());
            counts.computeIfAbsent(key, k -> new long[1])[0] += row.count();
            if (row.anyName() != null) {
                names.putIfAbsent(key, row.anyName());
            }
        }
        for (var row : liveStatus) {
            var key = new StatusKey(row.definitionId(), row.status().name());
            counts.computeIfAbsent(key, k -> new long[1])[0] += row.count();
            if (row.anyName() != null) {
                names.putIfAbsent(key, row.anyName());
            }
        }
        var statusCounts = counts.entrySet().stream()
                .map(e -> new DefinitionStatusCount(e.getKey().definitionId(),
                        ProcessStatus.valueOf(e.getKey().status()), e.getValue()[0],
                        names.get(e.getKey())))
                .toList();

        var createdPerDay = perDay(createdRows.stream()
                .map(r -> new DayCount(r.definitionId(), r.day(), r.count())).toList());
        var finishedPerDay = perDay(finishedRows.stream()
                .map(r -> new DayCount(r.definitionId(), r.finishedDay(), r.count())).toList());

        // Durations: sum the samples and totals, merge the histograms, read the p95 back off.
        Map<String, DurationAccumulator> durations = new LinkedHashMap<>();
        for (var row : durationRows) {
            durations.computeIfAbsent(row.definitionId(), k -> new DurationAccumulator())
                    .add(row.samples(), row.totalNanos(), row.histogram());
        }
        var durationAggregates = durations.entrySet().stream()
                .map(e -> new DefinitionDuration(e.getKey(), e.getValue().toAggregate()))
                .toList();

        return new ProcessAggregates(statusCounts, createdPerDay, finishedPerDay, durationAggregates);
    }

    public static StepAggregates reduceSteps(List<StepStatusDaily> statusRows,
                                             List<LiveStepStatus> liveStatus,
                                             List<StepDurationDaily> durationRows) {
        // First order is per (definition, step), the lowest _order any execution of it ran with —
        // so it is the min across every day-and-status bucket, and across the live overlay too.
        Map<StepKey, Long> firstOrder = new LinkedHashMap<>();
        Map<StepStatusKey, long[]> counts = new LinkedHashMap<>();
        for (var row : statusRows) {
            var stepKey = new StepKey(row.definitionId(), row.stepId());
            firstOrder.merge(stepKey, row.firstOrder(), Math::min);
            counts.computeIfAbsent(new StepStatusKey(stepKey, row.status().name()), k -> new long[1])[0]
                    += row.count();
        }
        for (var row : liveStatus) {
            var stepKey = new StepKey(row.definitionId(), row.stepId());
            firstOrder.merge(stepKey, row.firstOrder(), Math::min);
            counts.computeIfAbsent(new StepStatusKey(stepKey, row.status().name()), k -> new long[1])[0]
                    += row.count();
        }
        var countRows = counts.entrySet().stream()
                .map(e -> new DefinitionStepCount(e.getKey().step().definitionId(),
                        e.getKey().step().stepId(),
                        StepExecutionStatus.valueOf(e.getKey().status()), e.getValue()[0],
                        firstOrder.getOrDefault(e.getKey().step(), 0L)))
                .toList();

        Map<StepKey, DurationAccumulator> durations = new LinkedHashMap<>();
        for (var row : durationRows) {
            durations.computeIfAbsent(new StepKey(row.definitionId(), row.stepId()),
                    k -> new DurationAccumulator()).add(row.samples(), row.totalNanos(), row.histogram());
        }
        var durationRowsOut = durations.entrySet().stream()
                .map(e -> new DefinitionStepDuration(e.getKey().definitionId(), e.getKey().stepId(),
                        e.getValue().toAggregate()))
                .toList();

        return new StepAggregates(countRows, durationRowsOut);
    }

    private static List<DefinitionDayCount> perDay(List<DayCount> rows) {
        Map<DayKey, Long> counts = new LinkedHashMap<>();
        for (var row : rows) {
            counts.merge(new DayKey(row.definitionId(), row.day()), row.count(), Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new DefinitionDayCount(e.getKey().definitionId(), e.getKey().day(), e.getValue()))
                .toList();
    }

    private static final class DurationAccumulator {
        long samples;
        long total;
        final DurationHistogram histogram = DurationHistogram.empty();

        void add(long addedSamples, Long addedTotal, DurationHistogram addedHistogram) {
            samples += addedSamples;
            total += addedTotal == null ? 0 : addedTotal;
            if (addedHistogram != null) {
                histogram.mergeIn(addedHistogram);
            }
        }

        DurationAggregate toAggregate() {
            if (samples == 0) {
                return DurationAggregate.NONE;
            }
            return new DurationAggregate(samples, total, histogram.quantileNanos(0.95));
        }
    }

    private record StatusKey(String definitionId, String status) {
    }

    private record DayKey(String definitionId, LocalDate day) {
    }

    private record DayCount(String definitionId, LocalDate day, long count) {
    }

    private record StepKey(String definitionId, String stepId) {
    }

    private record StepStatusKey(StepKey step, String status) {
    }
}
