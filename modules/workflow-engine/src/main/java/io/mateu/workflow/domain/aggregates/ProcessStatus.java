package io.mateu.workflow.domain.aggregates;

public enum ProcessStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    ERROR,
    /**
     * Terminal state reached when a process failed and every executed rollbackable step was
     * successfully compensated (saga rollback), in reverse execution order. Distinct from
     * {@link #ERROR} — which means the process failed and did NOT (fully) roll back — and
     * sticky like it: nothing may transition a COMPENSATED process back to RUNNING/ERROR.
     */
    COMPENSATED
}
