package io.mateu.workflow.domain.aggregates;

public enum ProcessStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    ERROR,
    /**
     * Terminal state reached when a process failed and every executed compensable step was
     * successfully compensated (saga rollback), in reverse execution order. Distinct from
     * {@link #ERROR} — which means the process failed and did NOT (fully) roll back — and
     * sticky like it: nothing may transition a COMPENSATED process back to RUNNING/ERROR.
     */
    COMPENSATED,
    /**
     * Terminal state reached when a saga rollback could not complete: a compensation step itself
     * failed (its own retries exhausted), so the process is left <b>partially rolled back</b> —
     * some executed steps were undone, at least one was not. This is the most dangerous outcome a
     * saga can have and must never be silent: it is a distinct, sticky terminal state (not left in
     * ERROR) so operators can find and alert on it, and it notifies a parent PROCESS step as a
     * failure just like {@link #ERROR}. A stranger reading the instance must see that the rollback
     * halted here, and where.
     */
    COMPENSATION_FAILED
}
