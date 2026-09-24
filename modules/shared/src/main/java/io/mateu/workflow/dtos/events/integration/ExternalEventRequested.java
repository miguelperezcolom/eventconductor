package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * A domain event a {@code PUBLISH_EVENT} step emits for the outside world, written to the outbox in
 * the transaction that completes the step — so it is published if and only if the step completed —
 * and relayed from there: to the destination's topic in kafka mode, to the application's
 * {@code ExternalEventPublisher} in embedded mode.
 *
 * @param eventId              stable across relay retries and redeliveries (the step execution id):
 *                             consumers deduplicate on it
 * @param destination          the logical destination, mapped to a topic by configuration
 * @param eventType            the event's type (CloudEvents {@code type})
 * @param key                  the message key: the rendered key, else the business key, else the process id
 * @param format               {@code binary} / {@code structured} (CloudEvents) or {@code plain}
 * @param data                 the rendered payload, as JSON
 * @param time                 ISO-8601 instant the step completed
 */
public record ExternalEventRequested(
        String eventId,
        String destination,
        String eventType,
        String key,
        String format,
        String data,
        String processId,
        String workflowDefinitionId,
        String stepId,
        String businessKey,
        String time) implements DomainEvent {

    /**
     * The event's own key, so a topic partitioned by it keeps one entity's events in order. (It is
     * never consumed back by the engine: in kafka mode it goes to its destination topic, not to the
     * process's outbox topic.)
     */
    @Override
    public String partitionKey() {
        return key != null && !key.isBlank() ? key : processId;
    }
}
