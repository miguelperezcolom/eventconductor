package io.mateu.workflow.domain.events;

import io.mateu.workflow.domain.shared.DomainEvent;

public record ProcessCreated(
        String processId
) implements DomainEvent {
}
