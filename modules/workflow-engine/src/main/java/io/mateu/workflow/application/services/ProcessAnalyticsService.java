package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.AnalyticsAggregates;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Built-in process analytics, computed on demand from the {@link ProcessRepository}
 * and {@link StepExecutionRepository} ports — so the same numbers are available in
 * every deployment mode (embedded+memory, embedded+jpa, kafka+jpa) with no extra
 * infrastructure.
 *
 * <p>Per workflow definition and time window it aggregates: instance counts by
 * status, completion/error/cancellation rates, throughput per day, average and
 * p95 process duration, and average/p95 duration per step with the slowest step
 * flagged as the bottleneck.
 *
 * <p>Durations are only computed from instances/steps that carry both timestamps;
 * steps finished before the {@code finishedAt} field existed are counted but
 * excluded from duration stats.
 */
@Service
@RequiredArgsConstructor
public class ProcessAnalyticsService {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;

    /** Half-open on neither side: null bounds mean unbounded. Selection is by process creation time. */
    public record TimeWindow(LocalDateTime from, LocalDateTime to) {

        public static TimeWindow all() {
            return new TimeWindow(null, null);
        }

        public static TimeWindow lastDays(int days) {
            return new TimeWindow(LocalDateTime.now().minusDays(days), null);
        }

        public boolean contains(LocalDateTime moment) {
            if (moment == null) {
                return false;
            }
            return (from == null || !moment.isBefore(from)) && (to == null || !moment.isAfter(to));
        }
    }

    /** Average and p95 (nearest-rank) over {@code samples} measured durations. Null durations when no samples. */
    public record DurationStats(long samples, Duration average, Duration p95) {
        public static DurationStats of(List<Duration> durations) {
            if (durations.isEmpty()) {
                return new DurationStats(0, null, null);
            }
            var sorted = durations.stream().sorted().toList();
            var avg = sorted.stream().reduce(Duration.ZERO, Duration::plus).dividedBy(sorted.size());
            var p95 = sorted.get(Math.max(0, (int) Math.ceil(0.95 * sorted.size()) - 1));
            return new DurationStats(sorted.size(), avg, p95);
        }
    }

    public record StepAnalytics(
            String stepId,
            long executions,
            long completed,
            long failed,
            long active,
            DurationStats duration,
            boolean bottleneck) {}

    public record DefinitionAnalytics(
            String workflowDefinitionId,
            String workflowDefinitionName,
            long totalInstances,
            Map<ProcessStatus, Long> instancesByStatus,
            double completionRatePct,
            double errorRatePct,
            double cancellationRatePct,
            Map<LocalDate, Long> createdPerDay,
            Map<LocalDate, Long> finishedPerDay,
            DurationStats processDuration,
            List<StepAnalytics> steps,
            String bottleneckStepId) {}

    /**
     * One window's report material, already reduced by the stores. Two calls, a few dozen rows —
     * not the 383 215 the same report used to be folded from.
     */
    private record Snapshot(AnalyticsAggregates.ProcessAggregates processes,
                            AnalyticsAggregates.StepAggregates steps) {

        Map<String, String> namesByDefinition() {
            var names = new LinkedHashMap<String, String>();
            processes.statusCounts().forEach(count -> {
                if (count.anyProcessName() != null) {
                    names.putIfAbsent(count.workflowDefinitionId(), count.anyProcessName());
                }
            });
            return names;
        }

        /** Every definition that has a process in the window. */
        Set<String> definitionIds() {
            return processes.statusCounts().stream()
                    .map(AnalyticsAggregates.DefinitionStatusCount::workflowDefinitionId)
                    .filter(id -> id != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private Snapshot snapshot(TimeWindow window) {
        return new Snapshot(
                processRepository.aggregateProcesses(window.from(), window.to()),
                stepExecutionRepository.aggregateSteps(window.from(), window.to()));
    }

    /** Analytics for every known workflow definition (including ones with no instances in the window). */
    public List<DefinitionAnalytics> analyzeAll(TimeWindow window) {
        var snapshot = snapshot(window);
        var definitionIds = new LinkedHashSet<String>();
        workflowDefinitionRepository.findAll().forEach(def -> definitionIds.add(def.id()));
        definitionIds.addAll(snapshot.definitionIds());
        return definitionIds.stream()
                .map(definitionId -> analyzeDefinition(definitionId, snapshot))
                .toList();
    }

    /** Analytics for one definition, resolved by id or (case-insensitive) name. */
    public Optional<DefinitionAnalytics> analyze(String definitionIdOrName, TimeWindow window) {
        var snapshot = snapshot(window);
        return resolveDefinitionId(definitionIdOrName, snapshot)
                .map(definitionId -> analyzeDefinition(definitionId, snapshot));
    }

    private Optional<String> resolveDefinitionId(String definitionIdOrName, Snapshot snapshot) {
        if (definitionIdOrName == null || definitionIdOrName.isBlank()) {
            return Optional.empty();
        }
        var definitions = workflowDefinitionRepository.findAll();
        var byId = definitions.stream().filter(def -> definitionIdOrName.equals(def.id())).findFirst();
        if (byId.isPresent()) {
            return byId.map(def -> def.id());
        }
        var byName = definitions.stream()
                .filter(def -> definitionIdOrName.equalsIgnoreCase(def.name()))
                .findFirst();
        if (byName.isPresent()) {
            return byName.map(def -> def.id());
        }
        // Processes may reference a definition that was deleted since.
        return snapshot.definitionIds().stream().filter(definitionIdOrName::equals).findFirst();
    }

    private DefinitionAnalytics analyzeDefinition(String definitionId, Snapshot snapshot) {
        var byStatus = new EnumMap<ProcessStatus, Long>(ProcessStatus.class);
        snapshot.processes().statusCounts().stream()
                .filter(count -> definitionId.equals(count.workflowDefinitionId()))
                .forEach(count -> byStatus.merge(count.status(), count.count(), Long::sum));

        var total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        var steps = stepAnalytics(definitionId, snapshot);

        return new DefinitionAnalytics(
                definitionId,
                definitionName(definitionId, snapshot),
                total,
                byStatus,
                ratePct(byStatus.getOrDefault(ProcessStatus.COMPLETED, 0L), total),
                ratePct(byStatus.getOrDefault(ProcessStatus.ERROR, 0L), total),
                ratePct(byStatus.getOrDefault(ProcessStatus.CANCELLED, 0L), total),
                perDay(snapshot.processes().createdPerDay(), definitionId),
                perDay(snapshot.processes().finishedPerDay(), definitionId),
                durationOf(snapshot.processes().durations().stream()
                        .filter(d -> definitionId.equals(d.workflowDefinitionId()))
                        .map(AnalyticsAggregates.DefinitionDuration::duration)
                        .findFirst().orElse(AnalyticsAggregates.DurationAggregate.NONE)),
                steps,
                steps.stream().filter(StepAnalytics::bottleneck).map(StepAnalytics::stepId)
                        .findFirst().orElse(null));
    }

    private List<StepAnalytics> stepAnalytics(String definitionId, Snapshot snapshot) {
        var counts = snapshot.steps().counts().stream()
                .filter(count -> definitionId.equals(count.workflowDefinitionId()))
                .toList();
        if (counts.isEmpty()) {
            return List.of();
        }
        // Flow order, as the engine ran them: the lowest _order the step was ever dispatched with.
        var order = new LinkedHashMap<String, Long>();
        counts.forEach(count -> order.merge(count.stepId(), count.firstOrder(), Math::min));

        var durations = snapshot.steps().durations().stream()
                .filter(duration -> definitionId.equals(duration.workflowDefinitionId()))
                .collect(Collectors.toMap(AnalyticsAggregates.DefinitionStepDuration::stepId,
                        AnalyticsAggregates.DefinitionStepDuration::duration, (a, b) -> a));

        var stats = order.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> {
                    var stepId = entry.getKey();
                    var forStep = counts.stream().filter(c -> stepId.equals(c.stepId())).toList();
                    return new StepAnalytics(
                            stepId,
                            forStep.stream().mapToLong(AnalyticsAggregates.DefinitionStepCount::count).sum(),
                            count(forStep, StepExecutionStatus.COMPLETED),
                            count(forStep, StepExecutionStatus.ERROR) + count(forStep, StepExecutionStatus.TIMEOUT),
                            count(forStep, StepExecutionStatus.PENDING) + count(forStep, StepExecutionStatus.RUNNING),
                            durationOf(durations.getOrDefault(stepId, AnalyticsAggregates.DurationAggregate.NONE)),
                            false);
                })
                .toList();

        var slowest = stats.stream()
                .filter(step -> step.duration().average() != null)
                .max(Comparator.comparing(step -> step.duration().average()))
                .map(StepAnalytics::stepId);

        return stats.stream()
                .map(step -> slowest.filter(step.stepId()::equals).isPresent()
                        ? new StepAnalytics(step.stepId(), step.executions(), step.completed(),
                                step.failed(), step.active(), step.duration(), true)
                        : step)
                .toList();
    }

    /**
     * The two numbers a report shows, from the count and total the store returned.
     *
     * <p>The division is here rather than in SQL so it is the one Java always did: integer
     * nanoseconds, truncating, exactly as {@link Duration#dividedBy} does.
     */
    private static DurationStats durationOf(AnalyticsAggregates.DurationAggregate aggregate) {
        if (aggregate == null || aggregate.samples() == 0 || aggregate.totalNanos() == null) {
            return new DurationStats(0, null, null);
        }
        return new DurationStats(aggregate.samples(),
                Duration.ofNanos(aggregate.totalNanos() / aggregate.samples()),
                aggregate.p95Nanos() == null ? null : Duration.ofNanos(aggregate.p95Nanos()));
    }

    private static Map<LocalDate, Long> perDay(List<AnalyticsAggregates.DefinitionDayCount> counts,
                                               String definitionId) {
        var perDay = new TreeMap<LocalDate, Long>();
        counts.stream()
                .filter(count -> definitionId.equals(count.workflowDefinitionId()))
                .forEach(count -> perDay.merge(count.day(), count.count(), Long::sum));
        return perDay;
    }

    private String definitionName(String definitionId, Snapshot snapshot) {
        return workflowDefinitionRepository.findById(definitionId)
                .map(def -> def.name())
                .orElseGet(() -> snapshot.namesByDefinition().getOrDefault(definitionId, definitionId));
    }

    private static long count(List<AnalyticsAggregates.DefinitionStepCount> counts, StepExecutionStatus status) {
        return counts.stream().filter(c -> status.equals(c.status()))
                .mapToLong(AnalyticsAggregates.DefinitionStepCount::count).sum();
    }

    private static double ratePct(long part, long total) {
        return total == 0 ? 0d : Math.round(1000d * part / total) / 10d;
    }
}
