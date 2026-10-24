package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.out.events.PublishedEventDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded mode: an event the engine itself publishes — a user task escalation's — relayed off the
 * outbox and delivered ({@link PublishedEventDelivery}: the application's publisher, then the loopback).
 * A PUBLISH_EVENT step's event does not come here: it is a task of the built-in publish-event worker.
 * Absent in kafka mode, where the relay sends it straight to the destination's topic.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class ExternalEventRequestedHandler implements DomainEventHandler<ExternalEventRequested> {

    private final PublishedEventDelivery publishedEventDelivery;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ExternalEventRequested.class;
    }

    @Override
    public void handle(ExternalEventRequested event) {
        publishedEventDelivery.deliver(event);
    }
}
