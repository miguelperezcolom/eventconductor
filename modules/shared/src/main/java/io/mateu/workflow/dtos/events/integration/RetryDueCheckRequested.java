package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * Emitted by the timeout scheduler when a step parked in {@code AWAITING_RETRY} has waited out its
 * backoff and is due to be re-dispatched. Process-scoped, like {@link TimerCheckRequested}, so the
 * handler reloads and re-dispatches only that process's due retries.
 */
public record RetryDueCheckRequested(String processId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
