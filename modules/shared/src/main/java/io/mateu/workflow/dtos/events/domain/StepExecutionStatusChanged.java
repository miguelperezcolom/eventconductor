package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskStatus;

import java.util.List;

public record StepExecutionStatusChanged(String stepExecutionId, TaskStatus status, List<Variable> variables) implements DomainEvent {
}
