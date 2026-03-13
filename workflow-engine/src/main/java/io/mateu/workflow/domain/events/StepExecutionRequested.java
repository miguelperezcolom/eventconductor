package io.mateu.workflow.domain.events;

import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.shared.DomainEvent;

public record StepExecutionRequested(
        StepExecution stepExecution
) implements DomainEvent {
}
