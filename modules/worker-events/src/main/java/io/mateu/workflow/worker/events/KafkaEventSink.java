package io.mateu.workflow.worker.events;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.events.CloudEventMessages;
import io.mateu.workflow.worker.api.TaskFailure;
import org.springframework.cloud.stream.function.StreamBridge;

/**
 * Kafka mode: sends the event to its destination's topic as a CloudEvent ({@link CloudEventMessages}),
 * keyed, synchronously, with the trace headers of the task — a refused send fails the task, and the step's retries send it again.
 */
public class KafkaEventSink implements EventSink {

    private final StreamBridge streamBridge;
    private final EventsWorkerProperties properties;

    public KafkaEventSink(StreamBridge streamBridge, EventsWorkerProperties properties) {
        this.streamBridge = streamBridge;
        this.properties = properties;
    }

    @Override
    public void send(ExternalEventRequested event, io.mateu.workflow.dtos.TraceContext trace) {
        var destination = properties.getDestinations().get(event.destination());
        if (destination == null || destination.getTopic() == null || destination.getTopic().isBlank()) {
            // The engine checked it when the step started, so only this worker's configuration differing
            // from the engine's lands here — which no retry fixes.
            throw new TaskFailure("EVENT_DESTINATION", "destination '" + event.destination()
                    + "' has no topic configured (workflow.events.destinations." + event.destination() + ".topic)", false);
        }
        var message = CloudEventMessages.messageFor(event,
                CloudEventMessages.formatOf(event, destination.getFormat()));
        if (trace != null) {
            // The process's trace goes on with the event, as on every record the engine sends.
            var traced = org.springframework.messaging.support.MessageBuilder.fromMessage(message);
            trace.toHeaders().forEach((name, value) ->
                    traced.setHeader(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            message = traced.build();
        }
        if (!streamBridge.send(destination.getTopic(), message)) {
            throw new TaskFailure("EVENT_IO", "the broker refused the event for topic '" + destination.getTopic() + "'", true);
        }
    }
}
