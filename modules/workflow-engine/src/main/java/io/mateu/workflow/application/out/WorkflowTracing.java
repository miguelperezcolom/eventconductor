package io.mateu.workflow.application.out;

import java.time.Instant;
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

    /**
     * Emits a span for work that has <em>already happened</em>, from the timestamps the engine
     * durably recorded, and returns its own {@code traceparent} so children can be hung off it.
     *
     * <p>This is the one thing wrapping live code cannot do, and the reason a process could not be
     * traced as a process. A span around a running method lasts as long as that method and lives on
     * one thread; a workflow step lasts as long as the step — a {@code USER_TASK} can wait days —
     * and its start and its end happen in different transactions, usually on different pods, quite
     * possibly weeks apart. There is no call stack to wrap. What there <em>is</em> is
     * {@code startedAt} and {@code finishedAt} on the step execution row, and a process's
     * {@code created} and {@code finished}: the engine has always known exactly when each piece of
     * work ran, and this is what turns that record into a span.
     *
     * <p>Because the spans are built rather than observed, a process comes out as one trace with the
     * shape the process actually had — the steps in the order they ran, and steps that ran at the
     * same time overlapping as siblings, rather than nested by whichever event happened to dispatch
     * which.
     *
     * @param parentTraceParent the W3C {@code traceparent} of the parent; the span joins that trace
     * @param name              the span name, e.g. the step's name
     * @param startedAt         when the work began — not now
     * @param finishedAt        when it ended — not now
     * @param tags              attributes to hang on the span
     * @return the emitted span's own {@code traceparent}, or null if nothing was emitted
     */
    default String recordSpan(String parentTraceParent, String name, Instant startedAt, Instant finishedAt,
                              Map<String, String> tags) {
        return null;
    }

    /** {@link #continuing(String, String, Runnable)} with tags on the span it opens. */
    default void continuing(String traceParent, String spanName, Map<String, String> tags, Runnable work) {
        continuing(traceParent, spanName, work);
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
