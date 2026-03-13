package io.mateu.workflow.domain.aggregates;

public enum StepExecutionStatus {
    CREATED,
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR
}
