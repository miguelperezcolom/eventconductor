package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

public record TaskStatusChanged(String taskExecutionId, TaskStatus status) implements DomainEvent {
}
