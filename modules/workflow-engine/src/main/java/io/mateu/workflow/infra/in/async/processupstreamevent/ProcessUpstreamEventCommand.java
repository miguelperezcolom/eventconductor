package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.ddd.DomainEvent;

public record ProcessUpstreamEventCommand(DomainEvent event) {
}
