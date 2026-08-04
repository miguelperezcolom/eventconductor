package io.mateu.workflow.application.out;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The engine's hold on a trace: capturing the context it is running in, resuming it later, and
 * naming the work in between.
 *
 * <p>It exists because of the outbox. A domain event is written to a row inside the transaction
 * that produced it and published by a relay thread some time afterwards, so the publish happens
 * with nothing of the original context around it. Automatic instrumentation cannot bridge that —
 * it sees a database write in one trace and, later, an unrelated Kafka send — and the result is a
 * trace per hop rather than a trace per process. Carrying the {@code traceparent} across the row is
 * what makes "follow one process end to end" true rather than aspirational.
 *
 * <p>A port with a no-op default, exactly like {@link WorkflowMetrics}: the engine libraries run
 * with no observability dependencies at all, and a host that brings Micrometer tracing gets the
 * real implementation wired in its place. Nothing here throws, and nothing here changes behaviour
 * — a tracing failure must never be a workflow failure.
 */
public interface WorkflowTracing {

    WorkflowTracing NOOP = new WorkflowTracing() {};

    /**
     * The current trace as a W3C {@code traceparent}, or null when nothing is being traced —
     * which is the normal case, since tracing is off by default.
     */
    default String currentTraceParent() {
        return null;
    }

    /**
     * Runs {@code work} as a continuation of the trace {@code traceParent} came from, so what it
     * does belongs to the trace that produced the event rather than to the relay that found it.
     * A null or unreadable value simply runs the work as it is.
     */
    default void continuing(String traceParent, String spanName, Runnable work) {
        work.run();
    }

    /** Names a piece of engine work, so a trace shows what the engine was doing and for how long. */
    default <T> T span(String name, Map<String, String> tags, Supplier<T> work) {
        return work.get();
    }

    default void span(String name, Map<String, String> tags, Runnable work) {
        span(name, tags, () -> {
            work.run();
            return null;
        });
    }
}
