package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.dtos.events.integration.MessageReceived;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * The process this thread is driving inline, if any — what makes the outbox rows it writes arrive
 * already claimed by this pod instead of waiting for a relay.
 *
 * <p>Thread-scoped on purpose: everything the fast path runs — the handler, the step-over, an
 * embedded worker called inline and its reply — runs on the driving thread, so every row that
 * process writes on the way is claimed; anything that hops to another thread (a worker pool,
 * another pod's consumer) writes ordinary {@code Pending} rows and takes the normal path, which is
 * exactly the fallback wanted.
 */
public final class InlineDrive {

    /** Who is driving which process, and until when its claims hold without renewal. */
    public record Context(String processId, String pod, LocalDateTime claimUntil) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private InlineDrive() {
    }

    /** Runs {@code work} with {@code context} as this thread's drive, restoring whatever was there. */
    public static <T> T run(Context context, Supplier<T> work) {
        var previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * The claim a row for {@code event} should be written with, or null to write it {@code Pending}.
     *
     * <p>Only the driven process's own events — keyed by its id, or unkeyed (a step's log line) and
     * written on the driving thread, which is only ever working for that process — and never the
     * two that do not stay on this shard's {@code outbox} (see {@code RelayDestination}): a
     * {@link MessageReceived} may be for another shard, a {@link ProcessStatusChanged} may belong
     * to a remote projector, and a PUBLISH_EVENT's event leaves for its destination topic. Those always go through the relay, whatever the mode, so the fast path
     * never decides a destination itself.
     */
    public static Context claimFor(DomainEvent event) {
        var context = CURRENT.get();
        if (context == null || event instanceof MessageReceived || event instanceof ProcessStatusChanged
                || event instanceof io.mateu.workflow.dtos.events.integration.ExternalEventRequested) {
            return null;
        }
        String key;
        try {
            key = event.partitionKey();
        } catch (RuntimeException e) {
            return null;
        }
        return key == null || context.processId().equals(key) ? context : null;
    }
}
