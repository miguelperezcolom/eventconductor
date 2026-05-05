package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

public record TaskExecutionRequested(
        String taskExecutionId,
        String processId,
        String workflowDefinitionId,
        String stepId,
        String taskId,
        List<Variable> variables) implements DomainEvent {
}
