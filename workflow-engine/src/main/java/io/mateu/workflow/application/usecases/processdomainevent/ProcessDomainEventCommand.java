package io.mateu.workflow.application.usecases.processdomainevent;

import io.mateu.workflow.domain.shared.DomainEvent;

public record ProcessDomainEventCommand(DomainEvent event) {
}
