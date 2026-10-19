package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.aggregates.SchedulePause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.ScheduleCatalog;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.time.Moments;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single cron scan shared by both scheduler variants (memory and JPA). For every schedule of the
 * {@link ScheduleCatalog} whose workflow is ACTIVE, publishes one {@link ProcessCreationRequested} per
 * occurrence elapsed since the last scan:
 *
 * <ul>
 *   <li>a definition's {@code cronExpression} — the legacy shorthand: no variables, read in the JVM's
 *       zone, business key {@code <defId>-cron-<local occurrence>}, exactly as before schedules existed;</li>
 *   <li>each entry {@code i} of its {@code schedules} without an id — business key
 *       {@code <defId>-cron-s<i>-<occurrence with offset>};</li>
 *   <li>each {@code cron.yaml} entry without an id targeting it — business key
 *       {@code <defId>-cron-f<hash>-<occurrence with offset>}, the hash being of the entry's cron, zone
 *       and variables, so the same entry declared in two sources still starts one process;</li>
 *   <li>each schedule with an id, wherever declared (a definition, a {@code cron.yaml}, the UI or the
 *       API) — business key {@code <defId>-cron-<id>-<occurrence with offset>}.</li>
 * </ul>
 *
 * <p>A paused schedule ({@link SchedulePause}, by id) is left out, as is the second declaration of an
 * id. The business key is derived deterministically from the schedule and the occurrence, so
 * redeliveries and concurrent pods (whose in-memory last-fire clocks differ) collapse into a single
 * process instance through the creation idempotency guard. A schedule without a zone of its own is
 * read in {@code workflow.time.zone} ({@link Moments#defaultZone()}).
 *
 * <p>Occurrences are tracked per schedule from scheduler start: schedules missed while no node was
 * running are skipped, not replayed. The tracking key includes the schedule's content, so editing a
 * schedule starts it afresh instead of replaying the new expression since the old one last fired; a
 * paused schedule is not tracked, so resuming it does not replay what fell due during the pause.
 */
@Slf4j
class CronStarts {

    /** One scheduled start, resolved for a scan. */
    record ScheduledStart(Schedule schedule, ZoneId zone) {

        String trackingKey() {
            return schedule.trackingKey();
        }

        String definitionId() {
            return schedule.workflowId();
        }

        String businessKey(ZonedDateTime occurrence) {
            return schedule.businessKey(occurrence);
        }

        List<Variable> variables() {
            return variablesOf(schedule.schedule());
        }

        /** The next occurrence strictly after {@code after}, or empty when the expression has none. */
        Optional<ZonedDateTime> next(ZonedDateTime after) {
            return Optional.ofNullable(CronExpression.parse(schedule.schedule().cron()).next(after.withZoneSameInstant(zone)));
        }
    }

    /** A schedule's variables as the process variables each start is created with. */
    static List<Variable> variablesOf(CronSchedule schedule) {
        return schedule.variablesOrEmpty().entrySet().stream()
                .map(e -> new Variable(e.getKey(), e.getValue()))
                .toList();
    }

    static void fireDue(WorkflowDefinitionRepository workflowDefinitionRepository,
                        CronScheduleRepository cronScheduleRepository,
                        SchedulePauseRepository schedulePauseRepository,
                        Consumer<ProcessCreationRequested> sink,
                        Map<String, ZonedDateTime> lastFireTimes,
                        Instant now) {
        fireDue(workflowDefinitionRepository.findAll(),
                cronScheduleRepository == null ? List.of() : cronScheduleRepository.findAll(),
                schedulePauseRepository == null ? Set.of() : schedulePauseRepository.findAll().keySet(),
                sink, lastFireTimes, now);
    }

    static void fireDue(Collection<WorkflowDefinition> definitions,
                        Collection<CronFileSchedule> fileSchedules,
                        Consumer<ProcessCreationRequested> sink,
                        Map<String, ZonedDateTime> lastFireTimes,
                        Instant now) {
        fireDue(definitions, fileSchedules, Set.of(), sink, lastFireTimes, now);
    }

    static void fireDue(Collection<WorkflowDefinition> definitions,
                        Collection<CronFileSchedule> fileSchedules,
                        Set<String> pausedIds,
                        Consumer<ProcessCreationRequested> sink,
                        Map<String, ZonedDateTime> lastFireTimes,
                        Instant now) {
        var seen = new HashSet<String>();
        for (var start : scheduledStarts(definitions, fileSchedules, pausedIds)) {
            if (!seen.add(start.trackingKey())) {
                continue;   // the same schedule twice: one start is enough
            }
            fire(start, sink, lastFireTimes, now);
        }
        // A schedule that is gone (edited, removed, paused, its definition disabled) stops being tracked.
        lastFireTimes.keySet().retainAll(seen);
    }

    /**
     * The earliest next occurrence across the schedules {@link #fireDue} would fire — after the last
     * one fired for a tracked schedule, after {@code now} for one not tracked yet.
     */
    static Optional<Instant> nextOccurrence(WorkflowDefinitionRepository workflowDefinitionRepository,
                                            CronScheduleRepository cronScheduleRepository,
                                            SchedulePauseRepository schedulePauseRepository,
                                            Map<String, ZonedDateTime> lastFireTimes,
                                            Instant now) {
        Instant earliest = null;
        for (var start : scheduledStarts(workflowDefinitionRepository.findAll(),
                cronScheduleRepository == null ? List.of() : cronScheduleRepository.findAll(),
                schedulePauseRepository == null ? Set.of() : schedulePauseRepository.findAll().keySet())) {
            var last = lastFireTimes.getOrDefault(start.trackingKey(), now.atZone(start.zone()));
            var next = start.next(last);
            if (next.isPresent() && (earliest == null || next.get().toInstant().isBefore(earliest))) {
                earliest = next.get().toInstant();
            }
        }
        return Optional.ofNullable(earliest);
    }

    private static void fire(ScheduledStart start, Consumer<ProcessCreationRequested> sink,
                             Map<String, ZonedDateTime> lastFireTimes, Instant now) {
        var nowZoned = now.atZone(start.zone());
        var last = lastFireTimes.computeIfAbsent(start.trackingKey(), key -> nowZoned);
        var next = start.next(last);
        while (next.isPresent() && !next.get().toInstant().isAfter(now)) {
            var occurrence = next.get();
            var businessKey = start.businessKey(occurrence);
            log.info("Cron start for workflow definition '{}' (schedule {}, occurrence {}, key {})",
                    start.definitionId(), start.schedule().id(), occurrence, businessKey);
            // The scheduler is the engine acting for itself, not a caller: SYSTEM says so, so a
            // definition with flow-authorization requirements still runs on its schedule.
            sink.accept(new ProcessCreationRequested(start.definitionId(), businessKey, start.variables(),
                    null, AuthorizationContext.SYSTEM));
            last = occurrence;
            next = start.next(last);
        }
        lastFireTimes.put(start.trackingKey(), last);
    }

    static List<ScheduledStart> scheduledStarts(Collection<WorkflowDefinition> definitions,
                                                Collection<CronFileSchedule> fileSchedules) {
        return scheduledStarts(definitions, fileSchedules, Set.of());
    }

    /**
     * Every scheduled start that should fire: its workflow ACTIVE, not paused, the owning declaration
     * of its id. Invalid ones (unparseable cron, unknown zone) are logged and left out — a definition
     * loaded without validation (memory mode) cannot break the scan.
     */
    static List<ScheduledStart> scheduledStarts(Collection<WorkflowDefinition> definitions,
                                                Collection<CronFileSchedule> fileSchedules,
                                                Set<String> pausedIds) {
        var active = new HashSet<String>();
        for (var definition : definitions) {
            if (definition.id() != null && definition.status().accceptsNewInstances()) {
                active.add(definition.id());
            }
        }
        var starts = new ArrayList<ScheduledStart>();
        for (var schedule : ScheduleCatalog.of(definitions, fileSchedules)) {
            if (schedule.duplicateOf() != null || !active.contains(schedule.workflowId())
                    || pausedIds.contains(schedule.id())) {
                continue;   // a second declaration; unknown, disabled or archived workflow; paused
            }
            resolve(schedule).ifPresent(starts::add);
        }
        return starts;
    }

    private static Optional<ScheduledStart> resolve(Schedule schedule) {
        var cron = schedule.schedule().cron();
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            log.error("Invalid cron expression '{}' ({}) on workflow definition '{}', skipping",
                    cron, schedule.declaredIn(), schedule.workflowId());
            return Optional.empty();
        }
        var zone = schedule.effectiveZone();
        if (zone.isEmpty()) {
            log.error("Invalid zone '{}' ({}) on workflow definition '{}', skipping",
                    schedule.schedule().zone(), schedule.declaredIn(), schedule.workflowId());
            return Optional.empty();
        }
        return Optional.of(new ScheduledStart(schedule, zone.get()));
    }

    /** First 8 hex chars of a SHA-256 over the schedule's cron, zone and variables (in name order). */
    static String contentHash(CronSchedule schedule) {
        return Schedule.contentHash(schedule);
    }

    private CronStarts() {
    }
}
