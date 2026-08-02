package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * A worker reporting on the task it was given.
 *
 * @param processId echoed back from the {@link TaskExecutionRequested} the worker received, so
 *                  the reply can be routed to the pod that owns the process. Appended to the
 *                  record: a worker built against an older shared module leaves it null, and the
 *                  event then falls back to the unrouted behaviour rather than failing to
 *                  deserialize.
 */
public record TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables,
                                String processId) implements DomainEvent {

    /**
     * For workers that do not know the process — a third-party worker built against an older
     * shared module, or one that only kept the task id. The event then carries no key and is
     * handled by whichever pod receives it, exactly as before ownership existed.
     */
    public TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables) {
        this(taskExecutionId, status, variables, null);
    }

    @Override
    public String partitionKey() {
        return processId;
    }
}
