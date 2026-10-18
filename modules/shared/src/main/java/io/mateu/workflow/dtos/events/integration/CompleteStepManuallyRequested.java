package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * An operator asked to complete one step execution by hand — as if its worker had answered with
 * {@code outputVariables} — so the flow carries on past it. From the UI, the REST API or an MCP tool.
 *
 * <p>Published rather than executed in place, like the other operator commands: carried out by the pod
 * that owns the process, under its lock.
 *
 * @param reason      why — mandatory for a manual operation, recorded in the process's audit trail
 * @param requestedBy who asked (the caller's subject at the door), or null when nobody was identified
 */
public record CompleteStepManuallyRequested(String processId, String stepExecutionId,
                                            List<Variable> outputVariables, String reason,
                                            String requestedBy) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
