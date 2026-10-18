package io.mateu.workflow.application.httpworkers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * {@code eventconductor.workers.http.*}: what the HTTP worker transport is doing. Resolves the
 * registry on first use (null: no metrics, everything here is a no-op), for the reason
 * {@code WorkflowMetricsAutoConfiguration} gives — a registry looked up at bean creation can be one
 * that does not exist yet.
 */
public class HttpWorkerMetrics {

    static final String PREFIX = "eventconductor.workers.http.";

    private final Supplier<MeterRegistry> registrySupplier;
    private volatile MeterRegistry registry;
    private volatile boolean resolved;
    private volatile IntSupplier waiting = () -> 0;
    private final AtomicLong available = new AtomicLong();
    private final AtomicLong leased = new AtomicLong();

    public HttpWorkerMetrics(Supplier<MeterRegistry> registry) {
        this.registrySupplier = registry == null ? () -> null : registry;
    }

    public static HttpWorkerMetrics none() {
        return new HttpWorkerMetrics(() -> null);
    }

    private MeterRegistry registry() {
        if (!resolved) {
            synchronized (this) {
                if (!resolved) {
                    var found = registrySupplier.get();
                    if (found != null) {
                        Gauge.builder(PREFIX + "waiting", () -> waiting.getAsInt())
                                .description("Activations (long-polls) waiting for tasks on this node")
                                .register(found);
                        Gauge.builder(PREFIX + "queued", available, AtomicLong::get).tag("state", "available")
                                .description("Tasks queued for HTTP workers (cluster-wide: alert on the max across nodes)")
                                .register(found);
                        Gauge.builder(PREFIX + "queued", leased, AtomicLong::get).tag("state", "leased")
                                .description("Tasks queued for HTTP workers (cluster-wide: alert on the max across nodes)")
                                .register(found);
                    }
                    registry = found;
                    resolved = true;
                }
            }
        }
        return registry;
    }

    private void count(String name, String tagKey, String tagValue, long amount) {
        var meters = registry();
        if (meters == null || amount <= 0) {
            return;
        }
        Counter.builder(PREFIX + name).tag(tagKey, tagValue == null ? "" : tagValue).register(meters).increment(amount);
    }

    public void waitingGauge(IntSupplier waiting) {
        this.waiting = waiting;
        registry();
    }

    public void queueCounts(long available, long leased) {
        this.available.set(available);
        this.leased.set(leased);
        registry();
    }

    public void enqueued(String topic) {
        count("enqueued", "topic", topic, 1);
    }

    public void activated(String topic, boolean redelivery) {
        count("activated", "topic", topic, 1);
        if (redelivery) {
            count("redelivered", "topic", topic, 1);
        }
    }

    /** completed, failed, injected, stale, gone. */
    public void reply(String outcome) {
        count("replies", "outcome", outcome, 1);
    }

    public void cancelled(String outcome) {
        count("cancelled", "outcome", outcome, 1);
    }

    public void rejected(String reason) {
        count("rejected", "reason", reason, 1);
    }
}
