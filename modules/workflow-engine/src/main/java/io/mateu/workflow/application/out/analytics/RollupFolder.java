package io.mateu.workflow.application.out.analytics;

import io.mateu.workflow.application.out.analytics.RollupModel.CreatedRow;
import io.mateu.workflow.application.out.analytics.RollupModel.FinishedProcessRow;
import io.mateu.workflow.application.out.analytics.RollupModel.FinishedStepRow;
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
 * Folds a batch of raw rows into the rollup deltas for that batch — pure, so the same folding that
 * runs in the projector runs in a test with no database behind it.
 *
 * <p>A "delta" is a rollup row carrying only this batch's contribution: a count of one day's new
 * processes, a histogram of one batch's durations. The projector adds it into whatever the store
 * already holds for that key. Because the rows folded here are immutable facts — a process that has
 * finished, a step that has finished — a fact is folded exactly once, and adding deltas is safe.
 */
public final class RollupFolder {

    private RollupFolder() {
    }

    /** Each new process counts once, on its creation day. */
    public static List<ProcessCreatedDaily> foldCreated(List<CreatedRow> rows) {
        Map<DayKey, Long> counts = new LinkedHashMap<>();
        for (var row : rows) {
            counts.merge(new DayKey(row.definitionId(), row.createdDay()), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new ProcessCreatedDaily(e.getKey().definitionId(), e.getKey().day(), e.getValue()))
                .toList();
    }

    public record ProcessFinishedDeltas(List<ProcessFinishedDaily> finishedPerDay,
                                        List<ProcessStatusDaily> statusCounts,
                                        List<ProcessDurationDaily> durations) {
    }

    public static ProcessFinishedDeltas foldProcessFinished(List<FinishedProcessRow> rows) {
        Map<FinishedKey, Long> finished = new LinkedHashMap<>();
        Map<StatusKey, long[]> status = new LinkedHashMap<>();
        Map<StatusKey, String> names = new LinkedHashMap<>();
        Map<DayKey, DurationAccumulator> durations = new LinkedHashMap<>();

        for (var row : rows) {
            finished.merge(new FinishedKey(row.definitionId(), row.createdDay(), row.finishedDay()),
                    1L, Long::sum);

            var statusKey = new StatusKey(row.definitionId(), row.createdDay(), row.status().name());
            status.computeIfAbsent(statusKey, k -> new long[1])[0]++;
            if (row.name() != null) {
                names.putIfAbsent(statusKey, row.name());
            }

            if (row.durationNanos() != null) {
                durations.computeIfAbsent(new DayKey(row.definitionId(), row.createdDay()),
                        k -> new DurationAccumulator()).add(row.durationNanos());
            }
        }

        var finishedRows = finished.entrySet().stream()
                .map(e -> new ProcessFinishedDaily(e.getKey().definitionId(), e.getKey().createdDay(),
                        e.getKey().finishedDay(), e.getValue()))
                .toList();
        var statusRows = status.entrySet().stream()
                .map(e -> new ProcessStatusDaily(e.getKey().definitionId(), e.getKey().createdDay(),
                        ProcessStatus.valueOf(e.getKey().status()), e.getValue()[0], names.get(e.getKey())))
                .toList();
        var durationRows = durations.entrySet().stream()
                .map(e -> new ProcessDurationDaily(e.getKey().definitionId(), e.getKey().day(),
                        e.getValue().samples, e.getValue().total, e.getValue().histogram))
                .toList();
        return new ProcessFinishedDeltas(finishedRows, statusRows, durationRows);
    }

    public record StepFinishedDeltas(List<StepStatusDaily> statusCounts,
                                     List<StepDurationDaily> durations) {
    }

    public static StepFinishedDeltas foldStepFinished(List<FinishedStepRow> rows) {
        Map<StepStatusKey, long[]> status = new LinkedHashMap<>();
        Map<StepStatusKey, Long> firstOrder = new LinkedHashMap<>();
        Map<StepKey, DurationAccumulator> durations = new LinkedHashMap<>();

        for (var row : rows) {
            var statusKey = new StepStatusKey(row.definitionId(), row.stepId(), row.createdDay(),
                    row.status().name());
            status.computeIfAbsent(statusKey, k -> new long[1])[0]++;
            firstOrder.merge(statusKey, row.order(), Math::min);

            if (row.durationNanos() != null) {
                durations.computeIfAbsent(new StepKey(row.definitionId(), row.stepId(), row.createdDay()),
                        k -> new DurationAccumulator()).add(row.durationNanos());
            }
        }

        var statusRows = status.entrySet().stream()
                .map(e -> new StepStatusDaily(e.getKey().definitionId(), e.getKey().stepId(),
                        e.getKey().createdDay(), StepExecutionStatus.valueOf(e.getKey().status()),
                        e.getValue()[0], firstOrder.get(e.getKey())))
                .toList();
        var durationRows = durations.entrySet().stream()
                .map(e -> new StepDurationDaily(e.getKey().definitionId(), e.getKey().stepId(),
                        e.getKey().createdDay(), e.getValue().samples, e.getValue().total,
                        e.getValue().histogram))
                .toList();
        return new StepFinishedDeltas(statusRows, durationRows);
    }

    private static final class DurationAccumulator {
        long samples;
        long total;
        final DurationHistogram histogram = DurationHistogram.empty();

        void add(long nanos) {
            samples++;
            total += nanos;
            histogram.add(nanos);
        }
    }

    private record DayKey(String definitionId, LocalDate day) {
    }

    private record FinishedKey(String definitionId, LocalDate createdDay, LocalDate finishedDay) {
    }

    private record StatusKey(String definitionId, LocalDate createdDay, String status) {
    }

    private record StepKey(String definitionId, String stepId, LocalDate createdDay) {
    }

    private record StepStatusKey(String definitionId, String stepId, LocalDate createdDay, String status) {
    }
}
