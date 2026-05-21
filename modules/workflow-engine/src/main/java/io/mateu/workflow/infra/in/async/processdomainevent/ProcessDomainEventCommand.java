package io.mateu.workflow.infra.in.async.processdomainevent;

import io.mateu.workflow.ddd.DomainEvent;

public record ProcessDomainEventCommand(DomainEvent event) {
}
