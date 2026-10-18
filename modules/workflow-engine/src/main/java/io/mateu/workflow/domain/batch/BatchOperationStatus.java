package io.mateu.workflow.domain.batch;

/** Where a batch operation is. */
public enum BatchOperationStatus {
    /** Accepted; the runner has not reached it yet. */
    CREATED,
    /** The runner is walking its processes. */
    RUNNING,
    /** Every matching process was visited — each one succeeded, failed or was skipped (see the counters). */
    COMPLETED,
    /** The batch itself could not go on (its filter no longer resolves); the counters say how far it got. */
    FAILED,
    /** Cancelled by an operator: no further process is touched; what was done stays done. */
    CANCELLED;

    /** Whether the runner still has work here. */
    public boolean isActive() {
        return this == CREATED || this == RUNNING;
    }
}
