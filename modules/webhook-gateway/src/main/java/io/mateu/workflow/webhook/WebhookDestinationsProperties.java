package io.mateu.workflow.webhook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code workflow.events.destinations.<name>.topic} map, the same keys the engine's
 * {@code PUBLISH_EVENT} and inbound consumer use — so one set of environment variables names the
 * topics for every component. Only the topic is read here.
 */
@ConfigurationProperties(prefix = "workflow.events")
@Getter
@Setter
public class WebhookDestinationsProperties {

    private Map<String, Destination> destinations = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Destination {
        private String topic;
    }

    /** The topic of a destination, or null when none is configured. */
    public String topicOf(String destination) {
        var configured = destination == null ? null : destinations.get(destination);
        return configured == null || configured.getTopic() == null || configured.getTopic().isBlank() ? null
                : configured.getTopic();
    }
}
