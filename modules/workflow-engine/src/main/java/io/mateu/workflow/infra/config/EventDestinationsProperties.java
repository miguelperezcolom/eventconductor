package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where {@code PUBLISH_EVENT} steps publish: logical destination names mapped to topics. A definition
 * names a destination, never a topic — a definition imported from git must not be able to write into
 * the engine's own topics.
 *
 * <pre>
 *   workflow:
 *     events:
 *       destinations:
 *         bookings: { topic: booking-events }
 *         audit:    { topic: audit-events, format: plain }
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.events")
@Getter
@Setter
public class EventDestinationsProperties {

    private Map<String, Destination> destinations = new LinkedHashMap<>();

    /** Largest payload a PUBLISH_EVENT may render, in bytes of JSON; larger fails the step. */
    private long maxPayloadBytes = 262_144;

    @Getter
    @Setter
    public static class Destination {
        /** The topic the destination is published to (kafka mode). */
        private String topic;
        /** {@code binary} (default), {@code structured} or {@code plain}; a step may override. */
        private String format;
    }
}
