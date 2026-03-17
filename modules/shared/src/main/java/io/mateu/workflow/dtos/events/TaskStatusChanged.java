package io.mateu.workflow.dtos.events;

import io.mateu.workflow.ddd.DomainEvent;

public record TaskStatusChanged(String taskExecutionId, TaskStatus status) implements DomainEvent {
}
