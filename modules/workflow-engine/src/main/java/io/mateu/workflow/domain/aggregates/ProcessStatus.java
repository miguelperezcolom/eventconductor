package io.mateu.workflow.domain.aggregates;

public enum ProcessStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    ERROR
}
