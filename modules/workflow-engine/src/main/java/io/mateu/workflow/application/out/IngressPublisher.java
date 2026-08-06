package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * Publishes a new process's creation onto a <b>chosen</b> shard's {@code upstream-<shardId>} — the
 * counterpart of {@link CommandPublisher}, which resolves an existing process's shard; here the ingress
 * router has already picked the shard. Distinct from {@link UpstreamEventPublisher} (this shard's own
 * upstream): the ingress may place the process on a different shard than the one that took the request.
 */
public interface IngressPublisher {

    /** Send to {@code upstream-<shardId>} (keyed by the event's partition key), or this shard's own
     *  {@code upstream} when {@code shardId} is null/blank. */
    void publishToShard(DomainEvent event, String shardId);
}
