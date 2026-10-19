package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-topic rate limits on worker dispatch (2.33):
 * <pre>
 * workflow.workers.rate-limits.payments-api.permits=50
 * workflow.workers.rate-limits.payments-api.period=PT1S
 * </pre>
 * At most {@code permits} tasks of the topic are dispatched per {@code period} — to Kafka, to the HTTP worker
 * queue or to the embedded worker alike. Over it a task waits, its step PENDING and its timeout not yet running,
 * and is dispatched as permits free up: higher priority first, then one per process in turn. The topic is the
 * step's {@code topic} ({@code downstream} for a step that names none; {@code http-calls}, {@code llm-calls} for
 * the built-in tasks).
 */
@ConfigurationProperties(prefix = "workflow.workers")
@Getter
@Setter
public class WorkerRateLimitProperties {

    /** Limits by topic; none by default. */
    private Map<String, Limit> rateLimits = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Limit {
        /** Tasks dispatched per period. */
        private int permits;
        /** The window the permits are counted in. */
        private Duration period = Duration.ofSeconds(1);

        public Limit() {
        }

        public Limit(int permits, Duration period) {
            this.permits = permits;
            this.period = period;
        }
    }

    /** The limit of a topic, if it has a usable one. */
    public java.util.Optional<Limit> limitOf(String topic) {
        var limit = topic == null ? null : rateLimits.get(topic);
        return limit == null || limit.getPermits() <= 0 || limit.getPeriod() == null || limit.getPeriod().isZero()
                || limit.getPeriod().isNegative() ? java.util.Optional.empty() : java.util.Optional.of(limit);
    }
}
