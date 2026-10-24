package io.mateu.workflow.worker.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.worker.api.TaskContext;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskHandler;

import java.util.Map;

/**
 * The built-in {@code publish-event} task: sends the event a PUBLISH_EVENT step's engine rendered to
 * its destination, and completes the step (or, with an {@code awaitReply}, leaves it to its reply).
 *
 * <p>The event is rendered once, when the step starts, and travels in the task: a redelivered task sends
 * the same event with the same {@code eventId}, so delivery is at-least-once and consumers deduplicate
 * on it ({@code ce_id}). A send that fails is retried by the step's {@code retries}; a destination that
 * is not configured fails it for good.
 */
public class PublishEventHandler implements TaskHandler<PublishEventHandler.Input, Map<String, Object>> {

    /**
     * The task variable the engine renders the event into. The task carries every process variable too;
     * the event is already rendered, so they are ignored.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(JsonNode __event) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final EventSink sink;

    public PublishEventHandler(EventSink sink) {
        this.sink = sink;
    }

    @Override
    public Map<String, Object> handle(Input input, TaskContext context) throws Exception {
        var event = eventOf(input);
        if (sink == null) {
            throw new TaskFailure("EVENT_SINK", "no event sink: this worker runs neither in kafka mode nor next to"
                    + " the engine (workflow.mode)", false);
        }
        sink.send(event, context == null ? null : context.traceContext());
        return Map.of();
    }

    static ExternalEventRequested eventOf(Input input) {
        var node = input == null ? null : input.__event();
        if (node != null && node.isTextual()) {
            try {
                node = JSON.readTree(node.asText());
            } catch (Exception e) {
                node = null;
            }
        }
        if (node == null || !node.isObject()) {
            throw new TaskFailure("EVENT_REQUEST", "the task carries no rendered event", false);
        }
        ExternalEventRequested event;
        try {
            event = JSON.treeToValue(node, ExternalEventRequested.class);
        } catch (Exception e) {
            throw new TaskFailure("EVENT_REQUEST", "the task's event cannot be read: " + e.getMessage(), false);
        }
        if (blank(event.eventId()) || blank(event.destination()) || blank(event.eventType())) {
            throw new TaskFailure("EVENT_REQUEST", "the task's event has no id, destination or type", false);
        }
        return event;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
