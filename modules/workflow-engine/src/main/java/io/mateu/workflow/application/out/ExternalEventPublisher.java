package io.mateu.workflow.application.out;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;

/**
 * Delivers a {@code PUBLISH_EVENT} step's event in embedded mode — where there is no broker of the
 * engine's own. The default publishes a Spring application event ({@code ExternalEventPublished}); an
 * application that has a broker provides its own bean.
 *
 * <p>Called from the outbox relay, after the step's transaction committed: throwing leaves the event
 * in the outbox to be retried (at-least-once — consumers deduplicate on {@code eventId}).
 */
public interface ExternalEventPublisher {

    void publish(ExternalEventRequested event);
}
