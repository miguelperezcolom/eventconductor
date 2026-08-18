package io.mateu.workflow.application.out;

/**
 * Decides, <b>exactly once per business key</b>, which shard a process is placed on.
 *
 * <p>This is the correctness half of the fleet-wide read database, and it is deliberately not the
 * read model. The index is eventually consistent — it is fed by a projector over a topic — and an
 * eventually-consistent store cannot answer "does this key already exist" without a race: a
 * redelivered creation arriving before the projection catches up is round-robined to a different
 * shard, where the per-shard creation guard cannot see the original, and the fleet grows two
 * processes for one key. Two sets of side effects, on two shards, that nobody is watching for.
 *
 * <p>So placement is claimed synchronously, in one statement, before the creation is published. The
 * two stores share a database for operational convenience and nothing else: this one is authoritative
 * state, written on the critical path; the index is derived, disposable, and off it.
 *
 * <p><b>Cost.</b> One small insert per <em>process</em> — not per step. Sharding exists because a
 * single database cannot absorb the per-step write stream (every transition, plus its outbox row:
 * tens of fsync-bound writes per process). One placement row is smaller than a shard's write load by
 * the average step count of a workflow, which is the ratio by which one placement database serves
 * many shards.
 */
public interface ProcessPlacementRepository {

    /**
     * The shard this business key is placed on: {@code candidateShardId} if this call won the claim,
     * the incumbent's shard if another call got there first. Never null.
     *
     * <p>Implementations must be atomic — the winner and every loser have to come back with the same
     * answer — and must <b>not</b> swallow failures. A creation that cannot be claimed has to fail:
     * it is retryable at its source (a Kafka redelivery, a 503, a cron re-fire), whereas a duplicated
     * process is not repairable.
     */
    String claim(String businessKey, String candidateShardId);
}
