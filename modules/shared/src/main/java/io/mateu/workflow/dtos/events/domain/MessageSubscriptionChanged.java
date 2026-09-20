package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * A {@code WAIT_FOR_MESSAGE} step started or stopped waiting — the signal that populates the shared
 * message-subscription routing table (layer 2 of sharded message routing). {@code waiting=true} when
 * the step begins to wait (subscribe), {@code false} when it reaches a terminal status (unsubscribe).
 * Handled by each shard's projection, which stamps its own shard id onto the row.
 *
 * <p>Keyed by the process so it rides that process's partition; {@code stepExecutionId} is what the
 * subscription table is keyed by, so a subscribe upserts and an unsubscribe deletes by that id.
 */
public record MessageSubscriptionChanged(
        String stepExecutionId,
        String messageName,
        String correlationKey,
        boolean waiting,
        String processId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
