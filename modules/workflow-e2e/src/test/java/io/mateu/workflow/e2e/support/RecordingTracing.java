package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.out.WorkflowTracing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link WorkflowTracing} that keeps what it was asked to emit, so a test can assert on the shape
 * of a trace rather than on the calls that produced it.
 *
 * <p>It stands in for the Micrometer bridge and behaves the way that bridge does in the one respect
 * the tests depend on: a span joins the trace of the {@code traceparent} it is given, and the
 * {@code traceparent} it returns names itself as the parent for anything hung off it. That is what
 * lets a test rebuild the tree — which span is whose child, and over what interval — from nothing
 * but the recorded calls.
 */
public class RecordingTracing implements WorkflowTracing {

    /** One emitted span: who its parent is, what it is called, and exactly when it ran. */
    public record Recorded(String traceId, String spanId, String parentSpanId, String name,
                           Instant startedAt, Instant finishedAt, Map<String, String> tags, boolean live) {
    }

    private final List<Recorded> spans = new CopyOnWriteArrayList<>();
    private final AtomicLong nextSpanId = new AtomicLong();

    public List<Recorded> spans() {
        return List.copyOf(spans);
    }

    public void clear() {
        spans.clear();
    }

    /** The spans of one trace, in the order they were emitted. */
    public List<Recorded> spansOfTrace(String traceParent) {
        var traceId = traceIdOf(traceParent);
        return spans.stream().filter(span -> traceId.equals(span.traceId())).toList();
    }

    public static String traceIdOf(String traceParent) {
        return traceParent.split("-")[1];
    }

    public static String spanIdOf(String traceParent) {
        return traceParent.split("-")[2];
    }

    @Override
    public String recordSpan(String parentTraceParent, String name, Instant startedAt, Instant finishedAt,
                             Map<String, String> tags) {
        if (parentTraceParent == null || startedAt == null || finishedAt == null) {
            return null;
        }
        return add(parentTraceParent, name, startedAt, finishedAt, tags, false);
    }

    @Override
    public void continuing(String traceParent, String spanName, Map<String, String> tags, Runnable work) {
        if (traceParent != null) {
            var now = Instant.now();
            add(traceParent, spanName, now, now, tags, true);
        }
        // Whether or not a span was recorded, the work runs: tracing describes the work and never
        // decides whether it happens.
        work.run();
    }

    @Override
    public void continuing(String traceParent, String spanName, Runnable work) {
        continuing(traceParent, spanName, Map.of(), work);
    }

    /**
     * Whether this parent's trace is one the backend would keep.
     *
     * <p>Honoured here for the same reason a real deployment honours it: Spring Boot's sampler is
     * {@code ParentBased}, so a span built under a parent whose {@code traceparent} says
     * unsampled is dropped rather than exported. Without this the recorder would keep everything
     * and a test could not tell a sampling decision from a bug.
     */
    private static boolean sampledFlagOf(String traceParent) {
        var parts = traceParent.split("-");
        return parts.length < 4 || !"00".equals(parts[3]);
    }

    private String add(String parentTraceParent, String name, Instant startedAt, Instant finishedAt,
                       Map<String, String> tags, boolean live) {
        if (!sampledFlagOf(parentTraceParent)) {
            return null;
        }
        var traceId = traceIdOf(parentTraceParent);
        var spanId = String.format("%016x", nextSpanId.incrementAndGet());
        spans.add(new Recorded(traceId, spanId, spanIdOf(parentTraceParent), name,
                startedAt, finishedAt, new java.util.LinkedHashMap<>(tags), live));
        return String.join("-", "00", traceId, spanId, "01");
    }
}
