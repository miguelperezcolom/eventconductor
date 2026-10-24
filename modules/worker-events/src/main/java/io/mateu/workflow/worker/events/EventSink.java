package io.mateu.workflow.worker.events;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;

/**
 * Where the publish-event task sends an event: a destination's topic (kafka mode) or the engine's own
 * delivery (embedded mode). Throws when the event could not be sent: a {@link
 * io.mateu.workflow.worker.api.TaskFailure} says whether a retry can help, anything else is retried.
 */
public interface EventSink {

    /** @param trace the W3C context the engine dispatched the task in, to carry on the event; null for none */
    void send(ExternalEventRequested event, io.mateu.workflow.dtos.TraceContext trace) throws Exception;
}
