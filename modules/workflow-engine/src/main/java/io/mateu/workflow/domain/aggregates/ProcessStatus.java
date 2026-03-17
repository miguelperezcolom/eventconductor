package io.mateu.workflow.domain.aggregates;

public enum ProcessStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR
}
