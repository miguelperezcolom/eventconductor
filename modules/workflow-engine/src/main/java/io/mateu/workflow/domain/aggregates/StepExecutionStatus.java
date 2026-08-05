package io.mateu.workflow.domain.aggregates;

public enum StepExecutionStatus {
    CREATED,
    PENDING,
    RUNNING,
    /**
     * A step that failed but has retries left and is waiting out its backoff delay before being
     * re-dispatched. It carries a {@code deadlineAt} (the moment the backoff expires); the timeout
     * scheduler wakes it and the retry path returns it to {@link #CREATED} to run again. Distinct
     * from PENDING/RUNNING so the timeout path never mistakes a step deliberately waiting to retry
     * for one whose worker deadline has expired.
     */
    AWAITING_RETRY,
    COMPLETED,
    CANCELLED,
    ERROR,
    TIMEOUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == ERROR || this == TIMEOUT;
    }
}
