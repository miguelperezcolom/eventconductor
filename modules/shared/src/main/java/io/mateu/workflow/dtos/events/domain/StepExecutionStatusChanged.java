package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskStatus;

import java.util.List;

/**
 * @param processId the process the step belongs to. Carried so the event can be routed to the
 *                  pod that owns that process; appended to the record, so an event written by an
 *                  older version simply deserializes with a null key and falls back to the
 *                  unrouted behaviour instead of failing.
 */
public record StepExecutionStatusChanged(String stepExecutionId, TaskStatus status, List<Variable> variables,
                                         String processId) implements DomainEvent {

    public StepExecutionStatusChanged(String stepExecutionId, TaskStatus status, List<Variable> variables) {
        this(stepExecutionId, status, variables, null);
    }

    @Override
    public String partitionKey() {
        return processId;
    }
}
