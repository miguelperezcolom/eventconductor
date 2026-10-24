package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workers over HTTP: tasks for the listed topics are held in a durable queue and handed to workers
 * that long-poll {@code /workflow/api/workers/activate}, in any language, without Kafka.
 *
 * <pre>
 *   workflow:
 *     workers:
 *       http:
 *         enabled: true
 *         topics: [payments, downstream]   # or ["*"]: every topic but the built-in tasks'
 *         api-keys:
 *           python-payments: ${PAYMENTS_WORKER_KEY}   # X-Api-Key of a worker; grants the worker API only
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.workers.http")
@Getter
@Setter
public class HttpWorkersProperties {

    /** Whether tasks can be served over HTTP at all. Off by default. */
    private boolean enabled;

    /**
     * The step topics served over HTTP ({@code downstream} is the topic of a step that names none).
     * {@code *} routes every topic except the built-in tasks' (USER_TASK, RULE, HTTP_CALL, LLM_CALL, AGENT,
     * PUBLISH_EVENT),
     * which naming their topic explicitly still routes. Every other topic keeps going to the embedded
     * or Kafka workers.
     */
    private List<String> topics = new ArrayList<>();

    /**
     * Worker credentials, by name: the {@code X-Api-Key} a worker sends. They grant the worker API
     * (/workflow/api/workers/**) and nothing else. The engine's own {@code workflow.message-api.api-key}
     * (or the application's Spring Security) is accepted too.
     */
    private Map<String, String> apiKeys = new LinkedHashMap<>();

    /** The longest an activation may wait for tasks; a worker asking for more is capped. */
    private Duration maxWait = Duration.ofSeconds(30);

    /** The lease of an activation that does not ask for one. */
    private Duration defaultLease = Duration.ofSeconds(60);

    /** The longest lease (and heartbeat extension) a worker may ask for. */
    private Duration maxLease = Duration.ofMinutes(30);

    /** The most tasks one activation hands out. */
    private int maxTasks = 100;

    /** How many activations may be waiting on this node at once; past it, new ones get a 429. */
    private int maxWaiting = 200;

    /**
     * How often waiting activations look at the queue when nothing woke them — tasks enqueued by
     * another node, leases that expired. A task enqueued on this node wakes them at once.
     */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** How often cancelled and orphaned rows are purged and the queue gauges refreshed. */
    private Duration maintenanceInterval = Duration.ofSeconds(30);

    /**
     * How long a queued task may sit before it is considered orphaned (its step moved on without a
     * cancellation) and purged. Leased tasks are purged too: no worker holds one this long.
     */
    private Duration orphanRetention = Duration.ofDays(7);

    /** The name of the worker credential {@code key} matches, or null — compared in constant time. */
    public String workerCredential(String key) {
        if (key == null || apiKeys == null) {
            return null;
        }
        String found = null;
        var received = key.getBytes(StandardCharsets.UTF_8);
        for (var entry : apiKeys.entrySet()) {
            var expected = entry.getValue();
            if (expected != null && !expected.isBlank()
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), received)) {
                found = entry.getKey();
            }
        }
        return found;
    }
}
