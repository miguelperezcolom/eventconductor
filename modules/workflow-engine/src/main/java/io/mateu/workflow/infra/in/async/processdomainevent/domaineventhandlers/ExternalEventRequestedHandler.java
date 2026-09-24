package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ExternalEventPublisher;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded mode: a PUBLISH_EVENT's event, relayed off the outbox, handed to the application's
 * {@link ExternalEventPublisher}. Absent in kafka mode, where the relay sends it straight to the
 * destination's topic and it never comes back to a handler.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class ExternalEventRequestedHandler implements DomainEventHandler<ExternalEventRequested> {

    private final ExternalEventPublisher externalEventPublisher;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ExternalEventRequested.class;
    }

    @Override
    public void handle(ExternalEventRequested event) {
        externalEventPublisher.publish(event);
    }
}
