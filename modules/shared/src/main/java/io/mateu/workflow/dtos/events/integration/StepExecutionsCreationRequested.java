package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

public record StepExecutionsCreationRequested(String taskExecutionId, TaskStatus status) implements DomainEvent {
}
