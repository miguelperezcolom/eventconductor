package io.mateu.workflow.worker.http;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration of the built-in {@code http-call} task: named connections, named auth profiles, the
 * secrets they (and inline auth blocks) reference, and the allowlist for absolute URLs.
 *
 * <pre>
 *   workflow:
 *     http:
 *       connections:
 *         payments: { base-url: https://payments.internal, auth: payments-oauth, read-timeout: PT10S }
 *       auth:
 *         payments-oauth: { type: oauth2-client-credentials, token-uri: https://idp/token,
 *                           client-id: ec, client-secret: "${PAYMENTS_SECRET}" }
 *         partner-key:    { type: api-key, header: X-Api-Key, value: "${PARTNER_API_KEY}" }
 *       secrets:
 *         PARTNER_TOKEN: "${PARTNER_TOKEN_FROM_ENV}"      # what ${secret:PARTNER_TOKEN} in a definition resolves to
 *       allowed-hosts: [ "*.partner.com" ]
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.http")
@Getter
@Setter
public class HttpWorkerProperties {

    /** The Kafka topic the task is served on (a step's {@code topic} overrides it on the engine side). */
    private String topic = "http-calls";

    private Map<String, Connection> connections = new LinkedHashMap<>();

    private Map<String, AuthProfile> auth = new LinkedHashMap<>();

    /** Values for {@code ${secret:NAME}} references (fed from env vars, K8s secrets, Vault…). */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * Hosts an absolute URL may target: exact names or {@code *.suffix} patterns. Empty — the default —
     * allows any public host. Internal addresses (loopback, private, link-local, the cloud metadata
     * endpoint) are refused for absolute URLs regardless; reach internal services through a connection.
     */
    private List<String> allowedHosts = new ArrayList<>();

    /** Largest response body read, in bytes; a larger one fails the step. */
    private long maxResponseBytes = 1_048_576;

    /** Timeouts for absolute URLs (a connection has its own). */
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);

    @Getter
    @Setter
    public static class Connection {
        private String baseUrl;
        /** Default auth profile for calls on this connection; a step may name another. */
        private String auth;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);
        private Map<String, String> headers = new LinkedHashMap<>();
        /** Header carrying the step execution id so a retry is not applied twice; {@code none} to omit it. */
        private String idempotencyHeader = "Idempotency-Key";
    }

    @Getter
    @Setter
    public static class AuthProfile {
        /** {@code none}, {@code basic}, {@code bearer}, {@code api-key} or {@code oauth2-client-credentials}. */
        private String type = "none";
        private String username;
        private String password;
        private String token;
        /** api-key: the header name (or the query parameter name, with {@code in: query}). */
        private String header = "X-Api-Key";
        private String value;
        /** api-key: {@code header} (default) or {@code query}. */
        private String in = "header";
        private String tokenUri;
        private String clientId;
        private String clientSecret;
        private String scope;
    }
}
