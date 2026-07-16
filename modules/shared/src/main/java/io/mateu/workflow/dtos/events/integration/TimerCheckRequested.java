package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

public record TimerCheckRequested(String processId) implements DomainEvent {
}
