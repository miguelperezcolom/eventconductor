package io.mateu.workflow.infra.out.events;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;

/**
 * The Spring application event the default {@code ExternalEventPublisher} publishes in embedded mode.
 * Listen with {@code @EventListener} (or {@code @TransactionalEventListener} is not needed: it is
 * already published after the step's transaction committed).
 */
public record ExternalEventPublished(ExternalEventRequested event) {
}
