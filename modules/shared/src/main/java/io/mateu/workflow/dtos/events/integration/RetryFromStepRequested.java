package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * An operator asked to run a process again from one of its steps: that step and every step after it
 * go back to the state they were created in, and the flow runs from there. From the UI, the REST API
 * or an MCP tool.
 *
 * <p>Published rather than executed in place, like the other operator commands: carried out by the pod
 * that owns the process, under its lock.
 *
 * @param stepId      the step of the definition (not a step execution) to run again from
 * @param reason      why — mandatory for a manual operation, recorded in the process's audit trail
 * @param requestedBy who asked (the caller's subject at the door), or null when nobody was identified
 */
public record RetryFromStepRequested(String processId, String stepId, String reason, String requestedBy)
        implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
