package io.mateu.workflow.dtos.events;

import io.mateu.workflow.ddd.DomainEvent;

public record TaskCancellationRequested(String taskId) implements DomainEvent {
}
