package io.mateu.workflow.application.services;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Holds back, on this thread, the domain events that saves raise while a batch of writes is still
 * being made, and hands them over once the batch is done.
 *
 * <p>Memory mode only. In JPA mode a save writes its events to the outbox, and nothing acts on them
 * until the transaction that made every write of the batch has committed. The memory stores act on
 * an event the moment it is saved — on the same thread — and an embedded worker that answers inline
 * then drives the process on <em>in the middle</em> of the writes that started it: a FORK that has
 * saved its first branch but not yet its second sees that first branch run to END, which cancels the
 * second — and the outer loop then saves its own, stale copy of the second branch over the
 * cancellation, leaving it PENDING (and dispatched) in a process that has already completed. Batching
 * gives memory mode the ordering the outbox gives JPA: all of a step-over's writes land before any of
 * their consequences run.
 *
 * <p>Nested batches join the outermost one; events are handled in the order they were raised, after
 * it closes. An event handled then may open batches of its own, which drain the same way.
 */
public final class DeferredEvents {

    private record Pending(Object event, Consumer<Object> handler) {
    }

    private static final ThreadLocal<ArrayDeque<Pending>> QUEUE = new ThreadLocal<>();

    private DeferredEvents() {
    }

    /** Runs {@code writes}; events raised through {@link #raise} meanwhile are handled after it returns. */
    public static void batch(Runnable writes) {
        if (QUEUE.get() != null) {
            writes.run(); // part of an enclosing batch, which drains everything
            return;
        }
        var queue = new ArrayDeque<Pending>();
        QUEUE.set(queue);
        try {
            writes.run();
        } finally {
            QUEUE.remove();
        }
        // Outside the batch now: anything these handlers save is acted on as usual (or batched anew).
        Pending next;
        while ((next = queue.poll()) != null) {
            next.handler().accept(next.event());
        }
    }

    /** Handles {@code event} now, or — inside a batch — once the batch is done. */
    @SuppressWarnings("unchecked")
    public static <E> void raise(E event, Consumer<? super E> handler) {
        var queue = QUEUE.get();
        if (queue == null) {
            handler.accept(event);
        } else {
            queue.add(new Pending(event, (Consumer<Object>) handler));
        }
    }
}
