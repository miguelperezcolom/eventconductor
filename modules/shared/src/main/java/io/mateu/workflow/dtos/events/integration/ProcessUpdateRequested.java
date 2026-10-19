package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * A caller asked to apply a named update to a live process — from the REST API, an MCP tool, or produced
 * straight to the {@code upstream} topic. Carried out by the node that owns the process, under its lock,
 * which records the outcome on the update's row ({@code process_update}).
 *
 * <p>Idempotent by {@code updateId}: a redelivery finds the update already finished and does nothing.
 * Produced directly to Kafka, an update with no row yet is recorded by the owner (its payload validated
 * against the update's {@code accepts} there), so the topic is a door of its own.
 *
 * @param updateId       the update's id (and its idempotency key when produced directly)
 * @param processId      the process it is for
 * @param updateName     the update's name in the process's definition
 * @param payload        the payload as JSON text; null means none
 * @param requestedBy    who asked (the caller's subject at the door), or null when nobody was identified
 */
public record ProcessUpdateRequested(String updateId, String processId, String updateName, String payload,
                                     String requestedBy) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
