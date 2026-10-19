package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskQuery;
import io.mateu.workflow.time.EngineClock;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;

/**
 * The order an inbox lists tasks in, and the clock "overdue" is read against — UTC, the zone every
 * task moment is stored in, from {@link EngineClock} so a test that moves the engine's time moves this.
 *
 * <ul>
 *   <li>{@code PRIORITY}: most urgent first, then soonest due (tasks without a due date last), then
 *       oldest;</li>
 *   <li>{@code DUE}: soonest due first (none last), then most urgent;</li>
 *   <li>{@code CREATED}: newest first.</li>
 * </ul>
 * The id breaks every tie, so a page is stable.
 */
public final class TaskInboxOrder {

    private TaskInboxOrder() {
    }

    /** Now, in UTC. */
    public static LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(EngineClock.instant(), ZoneOffset.UTC);
    }

    public static Comparator<FormExecution> comparator(TaskQuery.Sort sort) {
        Comparator<FormExecution> byPriority = Comparator.comparingInt(FormExecution::priorityOrDefault).reversed();
        Comparator<FormExecution> byDue = Comparator.comparing(FormExecution::dueDate,
                Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<FormExecution> byCreated = Comparator.comparing(FormExecution::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<FormExecution> byId = Comparator.comparing(FormExecution::id,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return switch (sort) {
            case PRIORITY -> byPriority.thenComparing(byDue).thenComparing(byCreated).thenComparing(byId);
            case DUE -> byDue.thenComparing(byPriority).thenComparing(byId);
            case CREATED -> byCreated.reversed().thenComparing(byId);
        };
    }
}
