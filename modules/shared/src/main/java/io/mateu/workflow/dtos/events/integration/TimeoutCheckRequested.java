package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

public record TimeoutCheckRequested(String processId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
