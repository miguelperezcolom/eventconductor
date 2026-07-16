package io.mateu.workflow.domain.aggregates;

public enum StepExecutionStatus {
    CREATED,
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR,
    TIMEOUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == ERROR || this == TIMEOUT;
    }
}
