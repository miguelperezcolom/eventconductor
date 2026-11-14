package io.mateu.workflow.infra.in.ui.pages.process.childcruds;


import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Named;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One step execution as the process detail lists it.
 *
 * <p>Started and finished are on the row because they are what the list is scanned for once a
 * process is open: which step is taking the time, and how long the one still running has been at
 * it. The status alone says a step is RUNNING and not since when, which is the whole question when
 * an operator is deciding whether to wait or to intervene.
 *
 * @param attempts which attempt the step is on, against how many it may make — "2/3" for a step
 *                 retried once of its two retries, "1" for one that declares none — or empty for a
 *                 step that has not run. A retry reuses the execution, so without it the row of a
 *                 step that failed twice and then succeeded reads exactly like one that never failed.
 * @param started  when the step began, or empty while it has not — a step the process has created
 *                 but no worker has picked up yet.
 * @param finished when it reached a terminal status, or empty while it is still going.
 */
public record Step(@Hidden String processId, @Hidden String id, String name, Status status,
                   String attempts, String started, String finished) implements Named {

    /** Same stamp the process listing uses, so the two read as one clock. */
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * The Attempts cell: {@code attemptCount} (the retries already made) plus the one under way, over
     * the step's {@code retries} plus its first try; null for a step that has not been tried.
     */
    public static String attempts(int attemptCount, int retries, boolean tried) {
        if (!tried && attemptCount == 0) return null;
        var attempt = attemptCount + 1;
        return retries > 0 ? attempt + "/" + (retries + 1) : String.valueOf(attempt);
    }

    /** {@link #attempts(int, int, boolean)} for an execution, its retries read from the step it ran. */
    public static String attempts(io.mateu.workflow.domain.aggregates.StepExecution se) {
        var step = io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.safeStep(se.getStepJson());
        return attempts(se.getAttemptCount(), step == null ? 0 : step.retries(), tried(se.getStatus(), se.getStartedAt()));
    }

    /** Whether a step in this status has been tried: it started, or reached an outcome. */
    public static boolean tried(io.mateu.workflow.domain.aggregates.StepExecutionStatus status, LocalDateTime startedAt) {
        return startedAt != null || (status != io.mateu.workflow.domain.aggregates.StepExecutionStatus.CREATED
                && status != io.mateu.workflow.domain.aggregates.StepExecutionStatus.PENDING
                && status != io.mateu.workflow.domain.aggregates.StepExecutionStatus.WAITING_ON_LOCK
                && status != io.mateu.workflow.domain.aggregates.StepExecutionStatus.CANCELLED);
    }

    /** Formatted for the grid, or null when the moment has not arrived yet. */
    public static String format(LocalDateTime moment) {
        return moment != null ? moment.format(DTF) : null;
    }
}
