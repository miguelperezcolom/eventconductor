package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where {@code PUBLISH_EVENT} steps publish — and where event triggers and {@code onEvent} waits
 * listen: logical destination names mapped to topics. A definition
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

    /**
     * Embedded mode: an event a {@code PUBLISH_EVENT} step of this engine publishes is also received by
     * this engine — it can start a triggered workflow or complete an {@code onEvent} wait, as it would
     * through Kafka. Deduplicated like any delivery.
     */
    private boolean loopback = true;

    /** Kafka mode: how the destinations that triggers and {@code onEvent} waits name are consumed. */
    private Consumer consumer = new Consumer();

    /** The idempotency records of received events. */
    private Receipts receipts = new Receipts();

    @Getter
    @Setter
    public static class Consumer {
        /** Whether kafka mode consumes the destinations at all (REST, MCP and the API keep working). */
        private boolean enabled = true;
        /** The consumer group: one per engine deployment, so each event is used by one pod. */
        private String group = "eventconductor-inbound-events";
        /** Brokers; empty means spring.cloud.stream.kafka.binder.brokers, else spring.kafka.bootstrap-servers. */
        private String bootstrapServers;
        /** Where a new group starts: {@code latest} (default — enabling a trigger does not replay history) or {@code earliest}. */
        private String autoOffsetReset = "latest";
        /** How often the set of topics to consume is recomputed from the definitions. */
        private java.time.Duration refreshInterval = java.time.Duration.ofSeconds(30);
        /** Back-off between attempts at an event whose starts or messages could not be handed over. */
        private java.time.Duration retryBackoff = java.time.Duration.ofSeconds(5);
        /** Consumer threads. */
        private int concurrency = 1;
        /**
         * Kafka client properties for this consumer only, e.g. {@code sasl.jaas.config}: they override
         * {@code spring.kafka.*} and the binder's {@code configuration}, but not the brokers, group, offset
         * reset, manual commits or deserializers above.
         */
        private java.util.Map<String, String> properties = new java.util.LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Receipts {
        /** How long a receipt (and so the protection against a redelivered event) is kept. */
        private java.time.Duration retention = java.time.Duration.ofDays(7);
        /** How often old receipts are purged. */
        private java.time.Duration purgeInterval = java.time.Duration.ofMinutes(10);
    }

    @Getter
    @Setter
    public static class Destination {
        /** The topic the destination is published to (kafka mode). */
        private String topic;
        /** {@code binary} (default), {@code structured} or {@code plain}; a step may override. */
        private String format;
    }
}
