package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Micrometer-backed {@link WorkflowTracing}, bridged to OpenTelemetry by whatever the host brings.
 *
 * <p>The {@link Tracer} and {@link Propagator} are resolved on first use, not when this object is
 * built. The bean is wired early — the outbox relay and step dispatch depend on it — and the tracer
 * is created by a Spring Boot auto-configuration that a host which component-scans this package (its
 * {@code @SpringBootApplication} sits at {@code io.mateu.workflow}) has not run yet at that point,
 * so asking for it then gets {@code null}, which is exactly how the whole bridge silently fell back
 * to the no-op. By the time the first process starts or the relay turns, the tracer is there; until
 * it is (or if there is none — an app carrying the tracing classes but no configured bridge), every
 * method is a no-op that runs the work untraced.
 *
 * <p>Every method also swallows its own failures. Tracing is a description of the work, and a
 * description that goes wrong must not stop the work: an exporter that is down, a context that
 * cannot be parsed or a span that cannot be started leaves the process running exactly as it would
 * have without any of this.
 */
@Slf4j
public class MicrometerWorkflowTracing implements WorkflowTracing {

    /** The version field of a W3C traceparent; "00" is the only one defined. */
    private static final String W3C_VERSION = "00";

    private static final String TRACEPARENT = "traceparent";

    private final Supplier<Tracer> tracerSupplier;
    private final Supplier<Propagator> propagatorSupplier;
    private volatile Tracer resolvedTracer;
    private volatile Propagator resolvedPropagator;

    /** Eager: the beans are already in hand (tests, and the case where wiring order happens to work). */
    public MicrometerWorkflowTracing(Tracer tracer, Propagator propagator) {
        this.tracerSupplier = () -> tracer;
        this.propagatorSupplier = () -> propagator;
    }

    /** Lazy: the tracer and propagator are resolved on first use, dodging the bean-creation-order race. */
    public MicrometerWorkflowTracing(ObjectProvider<Tracer> tracerProvider,
                                     ObjectProvider<Propagator> propagatorProvider) {
        this.tracerSupplier = tracerProvider::getIfAvailable;
        this.propagatorSupplier = propagatorProvider::getIfAvailable;
    }

    /** The tracer once it exists, else {@code null}. Resolves each call until one appears, then caches. */
    private Tracer tracer() {
        var t = resolvedTracer;
        if (t == null) {
            t = tracerSupplier.get();
            if (t != null) {
                resolvedTracer = t;
            }
        }
        return t;
    }

    /** The propagator once it exists, else {@code null}. */
    private Propagator propagator() {
        var p = resolvedPropagator;
        if (p == null) {
            p = propagatorSupplier.get();
            if (p != null) {
                resolvedPropagator = p;
            }
        }
        return p;
    }

    @Override
    public String currentTraceParent() {
        var tracer = tracer();
        if (tracer == null) {
            return null;
        }
        try {
            var span = tracer.currentSpan();
            if (span == null) {
                return null;
            }
            var context = span.context();
            // Assembled rather than injected through the propagator: the carrier here is a database
            // column, not a set of message headers, and the format is fixed and small enough that
            // building it is clearer than adapting a map.
            return String.join("-", W3C_VERSION, context.traceId(), context.spanId(),
                    Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
        } catch (RuntimeException e) {
            log.debug("Could not read the current trace context", e);
            return null;
        }
    }

    @Override
    public void continuing(String traceParent, String spanName, Runnable work) {
        var tracer = tracer();
        var propagator = propagator();
        if (traceParent == null || traceParent.isBlank() || tracer == null || propagator == null) {
            work.run();
            return;
        }
        Span span = null;
        try {
            span = propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get)
                    .name(spanName)
                    .start();
        } catch (RuntimeException e) {
            log.debug("Could not resume the trace '{}'", traceParent, e);
        }
        if (span == null) {
            work.run();
            return;
        }
        try (var ignored = tracer.withSpan(span)) {
            work.run();
        } finally {
            span.end();
        }
    }

    @Override
    public <T> T span(String name, Map<String, String> tags, Supplier<T> work) {
        var tracer = tracer();
        if (tracer == null) {
            return work.get();
        }
        Span span = null;
        try {
            span = tracer.nextSpan().name(name);
            tags.forEach(span::tag);
            span.start();
        } catch (RuntimeException e) {
            log.debug("Could not start the span '{}'", name, e);
            span = null;
        }
        if (span == null) {
            return work.get();
        }
        try (var ignored = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
