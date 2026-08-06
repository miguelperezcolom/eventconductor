package io.mateu.workflow.autoconfigure;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tracing bridge, which nothing exercised.
 *
 * <p>Its whole contract is a negative one: <em>tracing never affects the work</em>. An exporter
 * that is down, a stored trace context that cannot be parsed, a tracer that throws — none of them
 * may stop a process from advancing. That is the kind of promise that regresses silently, because
 * the thing it protects is the absence of a failure: remove a try/catch and every test about
 * workflows still passes, right up until a tracing backend has a bad day in production and takes
 * the engine with it.
 *
 * <p>The other half is the `traceparent` string itself. It is written to an outbox column by one
 * pod and read by another, so its format is a wire contract between versions, not an internal
 * detail — asserted here as a literal W3C traceparent.
 */
class MicrometerWorkflowTracingTest {

    private final Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
    private final Propagator propagator = mock(Propagator.class);
    private final MicrometerWorkflowTracing tracing = new MicrometerWorkflowTracing(tracer, propagator);

    private static TraceContext context(String traceId, String spanId, boolean sampled) {
        var context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        when(context.spanId()).thenReturn(spanId);
        when(context.sampled()).thenReturn(sampled);
        return context;
    }

    private void currentSpanHas(TraceContext context) {
        var span = mock(Span.class);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
    }

    @Test
    void theTraceParentIsAW3cHeaderWithTheSampledFlagSet() {
        currentSpanHas(context("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true));

        assertThat(tracing.currentTraceParent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void anUnsampledContextIsStillPropagatedWithTheFlagClear() {
        currentSpanHas(context("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false));

        assertThat(tracing.currentTraceParent()).endsWith("-00");
    }

    @Test
    void noCurrentSpanMeansNoTraceParentRatherThanAnEmptyOne() {
        when(tracer.currentSpan()).thenReturn(null);

        assertThat(tracing.currentTraceParent()).isNull();
    }

    /** The outbox write must not fail because the tracer did. */
    @Test
    void aTracerThatThrowsYieldsNoTraceParentInsteadOfPropagatingTheFailure() {
        when(tracer.currentSpan()).thenThrow(new IllegalStateException("tracer is unhappy"));

        assertThat(tracing.currentTraceParent()).isNull();
    }

    @Test
    void withoutAStoredTraceParentTheWorkStillRuns() {
        var ran = new AtomicBoolean();

        tracing.continuing(null, "step-over", () -> ran.set(true));
        assertThat(ran).isTrue();

        ran.set(false);
        tracing.continuing("   ", "step-over", () -> ran.set(true));
        assertThat(ran).isTrue();

        verify(propagator, never()).extract(any(), any());
    }

    @Test
    void aResumedTraceRunsTheWorkInsideASpanAndEndsIt() {
        var span = mock(Span.class);
        var builder = mock(Span.Builder.class);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        var ran = new AtomicBoolean();

        tracing.continuing("00-abc-def-01", "step-over", () -> ran.set(true));

        assertThat(ran).isTrue();
        verify(builder).name("step-over");
        verify(span).end();
    }

    /**
     * The case this exists for: a `traceparent` written by an older version, or corrupted, must not
     * strand the process that carries it.
     */
    @Test
    void anUnparseableTraceParentStillRunsTheWork() {
        when(propagator.extract(any(), any())).thenThrow(new IllegalArgumentException("not a traceparent"));
        var ran = new AtomicBoolean();

        tracing.continuing("garbage", "step-over", () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void aSpanReturnsTheWorksValueAndCarriesItsTags() {
        var span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);

        var result = tracing.span("dispatch-step", Map.of("stepId", "s-1"), () -> "dispatched");

        assertThat(result).isEqualTo("dispatched");
        verify(span).name("dispatch-step");
        verify(span).tag("stepId", "s-1");
        verify(span).end();
    }

    /** A failure inside the work belongs on the span, but the caller still has to see it. */
    @Test
    void workThatThrowsMarksTheSpanAndRethrows() {
        var span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        var boom = new IllegalStateException("worker exploded");

        assertThatThrownBy(() -> tracing.span("dispatch-step", Map.of(), () -> {
            throw boom;
        })).isSameAs(boom);

        verify(span).error(boom);
        verify(span).end();
    }

    @Test
    void aSpanThatCannotBeStartedDoesNotStopTheWork() {
        when(tracer.nextSpan()).thenThrow(new IllegalStateException("no tracer"));

        assertThat(tracing.span("dispatch-step", Map.of("stepId", "s-1"), () -> "dispatched"))
                .isEqualTo("dispatched");
    }
}
