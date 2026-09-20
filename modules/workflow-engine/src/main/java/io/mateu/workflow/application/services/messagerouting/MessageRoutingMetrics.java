package io.mateu.workflow.application.services.messagerouting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Counters for message routing, so the effect on database load is measurable (Fase 4): messages by
 * route ({@code placement}, {@code subscription}, {@code broadcast}), placement lookup failures, and
 * correlation queries per shard. Metrics only activate when the host provides a {@link MeterRegistry}
 * (micrometer is optional); otherwise every call is a cheap no-op.
 */
@Component
public class MessageRoutingMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> routed = new ConcurrentHashMap<>();

    public MessageRoutingMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    /** One message routed via {@code route} (placement / subscription / broadcast). */
    public void routed(String route) {
        if (registry == null) {
            return;
        }
        routed.computeIfAbsent(route, r -> Counter.builder("eventconductor.messages.routed")
                .tag("route", r)
                .description("Messages routed, by route")
                .register(registry)).increment();
    }

    /** A placement-store lookup failed; the message fell through to the next layer. */
    public void placementLookupFailed() {
        if (registry != null) {
            registry.counter("eventconductor.messages.placement.lookup.failed").increment();
        }
    }

    /** A subscription-table lookup failed; the message fell through to broadcast. */
    public void subscriptionLookupFailed() {
        if (registry != null) {
            registry.counter("eventconductor.messages.subscription.lookup.failed").increment();
        }
    }

    /** A correlation query ran on this shard (its cost is what routing exists to reduce). */
    public void correlationQuery() {
        if (registry != null) {
            registry.counter("eventconductor.messages.correlation.queries").increment();
        }
    }
}
