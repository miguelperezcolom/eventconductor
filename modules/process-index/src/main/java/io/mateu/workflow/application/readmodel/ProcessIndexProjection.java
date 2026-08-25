package io.mateu.workflow.application.readmodel;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;

/**
 * The process-index projection itself — one event in, one upsert out — as a function rather than as
 * a piece of whichever runtime happens to be hosting it.
 *
 * <p>It exists because there are now <b>two</b> hosts: the engine's in-process
 * {@code ProcessStatusProjectionHandler} (embedded mode, projecting into the write database) and the
 * standalone projector (remote mode, projecting into a read database fed by the shared
 * {@code process-index} topic). Two implementations of one projection that must agree is precisely
 * how read models drift — one host gains a field, a rule, an ordering guard, and the other quietly
 * does not. There is one implementation, and both hosts call it.
 *
 * <p>Nothing here touches the write side: {@link ProcessStatusChanged} carries the whole projected
 * shape on purpose, which is what lets a projector run against a database that does not contain the
 * process at all.
 */
public final class ProcessIndexProjection {

    private ProcessIndexProjection() {
    }

    /**
     * Projects one status change into the index.
     *
     * <p>The row is stamped with the event's {@code occurredAt}, not the projector's clock. A
     * consume-time stamp would let a stale event clobber a newer state, and it can: a single node
     * dispatches a freshly-created process's events out of causal order — an in-process creation
     * cascade can run the process to completion before the creation's own seed event is dispatched.
     * {@code occurredAt} is stamped in causal order as the write side runs, and it is what the store's
     * upsert compares against to reject a late arrival.
     *
     * <p>The shard likewise rides the event, stamped on the owning shard, so a projector consuming
     * every shard's events records where each process lives rather than where it was projected.
     */
    public static void apply(ProcessIndexRepository store, ProcessStatusChanged event) {
        store.upsert(ProcessIndexRow.from(event, event.occurredAt(), event.shardId()));
    }
}
