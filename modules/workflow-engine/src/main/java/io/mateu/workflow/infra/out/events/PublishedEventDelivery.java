package io.mateu.workflow.infra.out.events;

import io.mateu.workflow.application.out.ExternalEventPublisher;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded mode: how an event leaves — handed to the application's {@link ExternalEventPublisher} and,
 * unless {@code workflow.events.loopback=false}, received by this engine's own event triggers and
 * {@code onEvent} waits, as it would be through Kafka. Used by the built-in publish-event worker (a
 * PUBLISH_EVENT step's event) and by the outbox relay (a user task escalation's).
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class PublishedEventDelivery {

    private final ExternalEventPublisher externalEventPublisher;
    /** Lazily: the inbound service reaches the engine's doors, which reach the outbox relay. */
    private final org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.services.InboundEventService> inboundEventService;
    private final EventDestinationsProperties eventDestinationsProperties;

    /**
     * Publishes {@code event}. After the publish, the loopback: the application sees its event whatever
     * happens there; a failure there retries the whole delivery, and the receipts keep the second pass
     * from acting twice. Throws when the event could not be delivered (at-least-once: consumers
     * deduplicate on {@code eventId}).
     */
    public void deliver(ExternalEventRequested event) {
        externalEventPublisher.publish(event);
        if (eventDestinationsProperties.isLoopback()) {
            inboundEventService.getObject().receive(io.mateu.workflow.domain.services.InboundEvents.fromPublished(event));
        }
    }
}
