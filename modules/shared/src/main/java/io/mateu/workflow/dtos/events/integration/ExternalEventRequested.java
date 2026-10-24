package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * An event for the outside world. A {@code PUBLISH_EVENT} step's is rendered by the engine when the step
 * starts and carried by its built-in {@code publish-event} task, whose worker sends it; a user task
 * escalation's is written to the outbox with the escalation and relayed by the engine. Either way it
 * goes to the destination's topic in kafka mode, to the application's {@code ExternalEventPublisher} in
 * embedded mode.
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
     * The event's own key, so a topic partitioned by it keeps one entity's events in order. (It never goes
     * back into the process's outbox topic: in kafka mode it goes to its destination topic — which the
     * engine consumes again only when an event trigger or an {@code onEvent} wait names that destination.)
     */
    @Override
    public String partitionKey() {
        return key != null && !key.isBlank() ? key : processId;
    }
}
