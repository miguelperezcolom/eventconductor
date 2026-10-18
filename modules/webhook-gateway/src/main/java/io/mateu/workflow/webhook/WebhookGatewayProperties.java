package io.mateu.workflow.webhook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code workflow.webhooks}: the endpoints the gateway exposes, one per webhook sender, and how the
 * events they produce reach the engine.
 *
 * <pre>
 * workflow:
 *   webhooks:
 *     transport: kafka                      # or rest (the engine's POST /workflow/api/events)
 *     endpoints:
 *       github:                             # POST /webhooks/github
 *         provider: github
 *         destination: github-events        # → workflow.events.destinations.github-events.topic
 *         secret: ${GITHUB_WEBHOOK_SECRET}  # the secret given to GitHub (X-Hub-Signature-256)
 *       partner:
 *         provider: generic
 *         destination: partner-events
 *         secret: ${PARTNER_WEBHOOK_SECRET} # optional: HMAC-SHA256 hex in signature-header
 *         type: com.partner.order.changed   # when the sender puts no type in a header
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.webhooks")
@Getter
@Setter
public class WebhookGatewayProperties {

    /** {@code false} leaves the endpoints unmapped. */
    private boolean enabled = true;

    /** The path the endpoints hang from: {@code POST <path>/<endpoint>}. */
    private String path = "/webhooks";

    /**
     * The largest webhook body accepted, in bytes; a larger one is refused with 413 before it is read
     * whole. 25 MB by default: GitHub's own payload cap, so no legitimate push is refused; lower it
     * for senders known to post small bodies.
     */
    private long maxBodyBytes = 25L * 1024 * 1024;

    /** {@code kafka}: the destination's topic, binary CloudEvents; {@code rest}: the engine's inject endpoint. */
    private Transport transport = Transport.KAFKA;

    /** The engine, for the {@code rest} transport. */
    private Engine engine = new Engine();

    /** Kafka, for the {@code kafka} transport. */
    private Kafka kafka = new Kafka();

    /** The endpoints by name — the last path segment of their URL. */
    private Map<String, Endpoint> endpoints = new LinkedHashMap<>();

    public enum Transport { KAFKA, REST }

    public enum Provider {
        /** GitHub: {@code X-Hub-Signature-256}, {@code X-GitHub-Event}, {@code X-GitHub-Delivery}. */
        GITHUB,
        /** Any sender: an optional HMAC-SHA256 signature and the attributes read from headers or fixed. */
        GENERIC
    }

    @Getter
    @Setter
    public static class Engine {
        /** The engine's base URL, e.g. {@code http://orchestrator:8080}. */
        private String url;
        /** Its {@code X-Api-Key}, when the message API requires one. */
        private String apiKey;
        /** How long a hand-over may take. */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(10);
    }

    @Getter
    @Setter
    public static class Kafka {
        /** Brokers; empty means {@code spring.cloud.stream.kafka.binder.brokers}, else {@code spring.kafka.bootstrap-servers}. */
        private String bootstrapServers;
        /** How long a send may take before the webhook is answered with an error. */
        private java.time.Duration sendTimeout = java.time.Duration.ofSeconds(10);
        /**
         * Kafka client properties for the gateway's producer only, e.g. {@code sasl.jaas.config}: they
         * override {@code spring.kafka.*} and the binder's {@code configuration}, but not the brokers,
         * serializers, {@code acks=all} or idempotence.
         */
        private Map<String, String> properties = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Endpoint {
        private Provider provider = Provider.GENERIC;
        /** The logical destination the event is published on (never a topic). */
        private String destination;
        /** The shared secret the sender signs with. Required for GitHub; optional for a generic sender. */
        private String secret;
        /** The CloudEvents {@code source}; default the endpoint's name. */
        private String source;
        /** GitHub: the type prefix — {@code com.github.} gives {@code com.github.push}, {@code com.github.pull_request.opened}. */
        private String typePrefix = "com.github.";
        /** Generic: the header carrying the signature (hex HMAC-SHA256 of the body, {@code sha256=} prefix tolerated). */
        private String signatureHeader = "X-Signature-256";
        /** Generic: the header carrying the CloudEvents type; absent or empty, {@link #type}. */
        private String typeHeader = "CE-Type";
        /** Generic: the type when no header carries one. */
        private String type;
        /** Generic: the header carrying the event id; absent, a random UUID (so every delivery is a new event). */
        private String idHeader = "CE-Id";
        /** Generic: the header carrying the subject (also the record key); optional. */
        private String subjectHeader = "CE-Subject";
    }

    /** The endpoint of that name, or null. */
    public Endpoint endpoint(String name) {
        return endpoints.get(name);
    }
}
