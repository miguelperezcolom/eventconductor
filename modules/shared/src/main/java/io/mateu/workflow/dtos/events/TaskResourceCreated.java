package io.mateu.workflow.dtos.events;

import io.mateu.workflow.ddd.DomainEvent;

public record TaskResourceCreated(String taskExecutionId, String resourceId, String resourceType, String resourceName, String resourceUrl) implements DomainEvent {
}
