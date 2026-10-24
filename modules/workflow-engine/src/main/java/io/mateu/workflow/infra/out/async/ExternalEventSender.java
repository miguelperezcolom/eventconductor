package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.events.CloudEventMessages;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;


/**
 * Kafka mode: sends an event the engine itself publishes — a user task escalation's — to its
 * destination's topic, keyed, synchronously; the same contract as every relayed event (a refused send
 * throws, the outbox row stays Pending). PUBLISH_EVENT steps are not sent here: they are a task of the
 * built-in publish-event worker. The record is {@link CloudEventMessages}'s, the same the worker sends.
 */
final class ExternalEventSender {

    private ExternalEventSender() {
    }

    static void send(StreamBridge streamBridge, EventDestinationsProperties destinations, ExternalEventRequested event) {
        send(streamBridge, destinations, event, null);
    }

    /** With the W3C trace headers of {@code trace} on the record; null sends none. */
    static void send(StreamBridge streamBridge, EventDestinationsProperties destinations, ExternalEventRequested event,
                     io.mateu.workflow.dtos.TraceContext trace) {
        var destination = destinations.getDestinations().get(event.destination());
        if (destination == null || destination.getTopic() == null || destination.getTopic().isBlank()) {
            // Validated when the step ran, so only a configuration changed underneath can land here. It
            // cannot succeed until someone fixes it, which is what a poison message is.
            throw new io.mateu.workflow.application.out.PoisonEventException(
                    "Event destination '" + event.destination() + "' has no topic configured"
                            + " (workflow.events.destinations." + event.destination() + ".topic)");
        }
        var message = PartitionedEvents.withTraceHeaders(messageFor(event, formatOf(event, destination)), trace);
        if (!streamBridge.send(destination.getTopic(), message)) {
            throw new PartitionedEvents.EventPublicationRefusedException(destination.getTopic(), event);
        }
    }

    static String formatOf(ExternalEventRequested event, EventDestinationsProperties.Destination destination) {
        return CloudEventMessages.formatOf(event, destination == null ? null : destination.getFormat());
    }

    static Message<byte[]> messageFor(ExternalEventRequested event, String format) {
        return CloudEventMessages.messageFor(event, format);
    }
}
