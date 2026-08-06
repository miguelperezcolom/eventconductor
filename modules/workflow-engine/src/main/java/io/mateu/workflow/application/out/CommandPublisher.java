package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * Routes an operator command that targets one process (retry / restart / pause / resume, keyed by
 * process id) to the <b>shard that owns that process</b>. The command is issued from a control plane
 * — a UI click, an MCP call — that does not know which shard the process lives on, so this resolves
 * the owning shard from the CQRS process-index ({@code shard_id}) and publishes to that shard's
 * {@code upstream}; keyed by process id, so within the shard it still reaches the pod that owns it.
 *
 * <p>Distinct from {@link UpstreamEventPublisher} (this shard's own upstream) and from
 * {@link MessagePublisher} (broadcast to all shards): a command has exactly one owner and must not be
 * broadcast, because the owner-only handlers ({@code findById(..).orElseThrow()}) would throw on every
 * other shard. {@code CommandDispatcher} chooses this vs the plain upstream from
 * {@code workflow.sharding.enabled}; with sharding off it is unused and commands go out exactly as before.
 */
public interface CommandPublisher {

    void publish(DomainEvent command);
}
