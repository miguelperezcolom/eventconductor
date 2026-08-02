package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * An operator asked to retry a single failed step.
 *
 * @param processId resolved where the request is taken, so the event can be routed to the pod
 *                  that owns the process — the step id alone does not say which partition that is.
 */
public record RetryStepExecutionRequested(String stepExecutionId, String processId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
