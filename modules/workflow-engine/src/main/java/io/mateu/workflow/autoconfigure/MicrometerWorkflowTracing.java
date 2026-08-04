package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Micrometer-backed {@link WorkflowTracing}, bridged to OpenTelemetry by whatever the host brings.
 *
 * <p>Only instantiated by {@code WorkflowTracingAutoConfiguration} when Micrometer tracing is on
 * the classpath and a {@code Tracer} bean exists — do not reference this class from code that must
 * run without it.
 *
 * <p>Every method swallows its own failures. Tracing is a description of the work, and a
 * description that goes wrong must not stop the work: an exporter that is down, a context that
 * cannot be parsed or a span that cannot be started leaves the process running exactly as it would
 * have without any of this.
 */
@RequiredArgsConstructor
@Slf4j
public class MicrometerWorkflowTracing implements WorkflowTracing {

    /** The version field of a W3C traceparent; "00" is the only one defined. */
    private static final String W3C_VERSION = "00";

    private static final String TRACEPARENT = "traceparent";

    final Tracer tracer;
    final Propagator propagator;

    @Override
    public String currentTraceParent() {
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
        if (traceParent == null || traceParent.isBlank()) {
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
