package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * An operator asked to RetryProcess — from the UI, the REST API or an MCP tool.
 *
 * <p>Published rather than executed in place: the request arrives at whichever pod took the call,
 * which is not the one that owns the process. Keyed by the process, it is handled by the pod that
 * does, so an operator action goes through the same single writer as everything else instead of
 * being the one path that needs a lock to be safe.
 */
public record RetryProcessRequested(String processId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
