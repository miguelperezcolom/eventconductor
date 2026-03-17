package io.mateu.workflow.application.usecases.processdomainevent;

import io.mateu.workflow.ddd.DomainEvent;

public record ProcessDomainEventCommand(DomainEvent event) {
}
