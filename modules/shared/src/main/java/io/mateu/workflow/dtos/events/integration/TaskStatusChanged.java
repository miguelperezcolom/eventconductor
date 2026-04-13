package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

public record TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables) implements DomainEvent {
}
