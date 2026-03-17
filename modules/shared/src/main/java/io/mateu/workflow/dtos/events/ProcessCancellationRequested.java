package io.mateu.workflow.dtos.events;

import io.mateu.workflow.ddd.DomainEvent;

public record ProcessCancellationRequested(String businessKey) implements DomainEvent {
}
