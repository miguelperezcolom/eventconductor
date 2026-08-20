package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessAnalyticsRow;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionAnalyticsRow;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
     * Everything a report over one window is computed from, read once. Both halves are narrow
     * projections, not aggregates: the point of gathering them here is that they are gathered
     * <b>once</b> — the step executions used to be re-read from scratch for every definition.
     */
    private record Snapshot(List<ProcessAnalyticsRow> processes,
                            Map<String, List<StepExecutionAnalyticsRow>> stepsByProcessId) {}

    private Snapshot snapshot(TimeWindow window) {
        var processes = processRepository.findAnalyticsRows(window.from(), window.to());
        var stepsByProcessId = stepExecutionRepository
                .findAnalyticsRows(window.from(), window.to()).stream()
                .collect(Collectors.groupingBy(StepExecutionAnalyticsRow::processId));
        return new Snapshot(processes, stepsByProcessId);
    }

    /** Analytics for every known workflow definition (including ones with no instances in the window). */
    public List<DefinitionAnalytics> analyzeAll(TimeWindow window) {
        var snapshot = snapshot(window);
        var definitionIds = new LinkedHashSet<String>();
        workflowDefinitionRepository.findAll().forEach(def -> definitionIds.add(def.id()));
        snapshot.processes().stream()
                .map(ProcessAnalyticsRow::workflowDefinitionId)
                .filter(id -> id != null)
                .forEach(definitionIds::add);
        return definitionIds.stream()
                .map(definitionId -> analyzeDefinition(definitionId, snapshot, window))
                .toList();
    }

    /** Analytics for one definition, resolved by id or (case-insensitive) name. */
    public Optional<DefinitionAnalytics> analyze(String definitionIdOrName, TimeWindow window) {
        var snapshot = snapshot(window);
        return resolveDefinitionId(definitionIdOrName, snapshot)
                .map(definitionId -> analyzeDefinition(definitionId, snapshot, window));
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
        return snapshot.processes().stream()
                .map(ProcessAnalyticsRow::workflowDefinitionId)
                .filter(definitionIdOrName::equals)
                .findFirst();
    }

    private DefinitionAnalytics analyzeDefinition(String definitionId, Snapshot snapshot, TimeWindow window) {
        var instances = snapshot.processes().stream()
                .filter(process -> definitionId.equals(process.workflowDefinitionId()))
                .filter(process -> window.contains(process.created()))
                .toList();

        var byStatus = new EnumMap<ProcessStatus, Long>(ProcessStatus.class);
        instances.forEach(process -> byStatus.merge(process.status(), 1L, Long::sum));

        var total = instances.size();
        var createdPerDay = perDay(instances, ProcessAnalyticsRow::created);
        var finishedPerDay = perDay(instances, ProcessAnalyticsRow::finished);

        var processDuration = DurationStats.of(instances.stream()
                .filter(process -> process.finished() != null)
                .map(ProcessAnalyticsService::durationOf)
                .filter(duration -> duration != null)
                .toList());

        var steps = stepAnalytics(instances, snapshot);
        var bottleneckStepId = steps.stream()
                .filter(StepAnalytics::bottleneck)
                .map(StepAnalytics::stepId)
                .findFirst().orElse(null);

        return new DefinitionAnalytics(
                definitionId,
                definitionName(definitionId, instances),
                total,
                byStatus,
                ratePct(byStatus.getOrDefault(ProcessStatus.COMPLETED, 0L), total),
                ratePct(byStatus.getOrDefault(ProcessStatus.ERROR, 0L), total),
                ratePct(byStatus.getOrDefault(ProcessStatus.CANCELLED, 0L), total),
                createdPerDay,
                finishedPerDay,
                processDuration,
                steps,
                bottleneckStepId);
    }

    private List<StepAnalytics> stepAnalytics(List<ProcessAnalyticsRow> instances, Snapshot snapshot) {
        var executionsByStep = new LinkedHashMap<String, List<StepExecutionAnalyticsRow>>();
        // Looked up per instance rather than scanned per definition. The scan was over every step
        // execution in the system, and it ran once for each definition on the page.
        instances.stream()
                .map(ProcessAnalyticsRow::id)
                .flatMap(processId -> snapshot.stepsByProcessId()
                        .getOrDefault(processId, List.of()).stream())
                .sorted(Comparator.comparingLong(StepExecutionAnalyticsRow::order))
                .forEach(execution -> executionsByStep
                        .computeIfAbsent(execution.stepId(), stepId -> new ArrayList<>())
                        .add(execution));

        var stats = executionsByStep.entrySet().stream()
                .map(entry -> {
                    var executions = entry.getValue();
                    var duration = DurationStats.of(executions.stream()
                            .filter(e -> e.startedAt() != null && e.finishedAt() != null)
                            .map(e -> Duration.between(e.startedAt(), e.finishedAt()))
                            .toList());
                    return new StepAnalytics(
                            entry.getKey(),
                            executions.size(),
                            count(executions, StepExecutionStatus.COMPLETED),
                            count(executions, StepExecutionStatus.ERROR) + count(executions, StepExecutionStatus.TIMEOUT),
                            count(executions, StepExecutionStatus.PENDING) + count(executions, StepExecutionStatus.RUNNING),
                            duration,
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

    private String definitionName(String definitionId, List<ProcessAnalyticsRow> instances) {
        return workflowDefinitionRepository.findById(definitionId)
                .map(def -> def.name())
                .orElseGet(() -> instances.stream()
                        .map(ProcessAnalyticsRow::name)
                        .filter(name -> name != null)
                        .findFirst()
                        .orElse(definitionId));
    }

    private static Map<LocalDate, Long> perDay(List<ProcessAnalyticsRow> instances,
                                              Function<ProcessAnalyticsRow, LocalDateTime> timestamp) {
        var perDay = new TreeMap<LocalDate, Long>();
        instances.stream()
                .map(timestamp)
                .filter(moment -> moment != null)
                .forEach(moment -> perDay.merge(moment.toLocalDate(), 1L, Long::sum));
        return perDay;
    }

    private static Duration durationOf(ProcessAnalyticsRow process) {
        var start = process.started() != null ? process.started() : process.created();
        if (start == null || process.finished() == null) {
            return null;
        }
        return Duration.between(start, process.finished());
    }

    private static long count(List<StepExecutionAnalyticsRow> executions, StepExecutionStatus status) {
        return executions.stream().filter(execution -> status.equals(execution.status())).count();
    }

    private static double ratePct(long part, long total) {
        return total == 0 ? 0d : Math.round(1000d * part / total) / 10d;
    }
}
