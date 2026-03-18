package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskStatus;

public record StepExecutionStatusChanged(String stepExecutionId, String processId, TaskStatus status) implements DomainEvent {
}
