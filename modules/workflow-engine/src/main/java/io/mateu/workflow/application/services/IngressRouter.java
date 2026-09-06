package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.application.out.ShardRegistry;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Places a new process on a shard — the one point in elastic sharding where a shard is <b>chosen</b>.
 *
 * <p>Two rules, in order:
 * <ol>
 *   <li><b>Idempotency wins.</b> A business key is placed exactly once, and every later request for
 *       that key goes to the shard it was placed on. A retry, a redelivery, or cron's deterministic
 *       per-occurrence key must land back on the same shard, or the per-shard creation idempotency
 *       guard cannot collapse the duplicate and the fleet grows two processes for one key.</li>
 *   <li><b>New keys spread.</b> Otherwise pick an {@code active} shard round-robin. Round-robin, not
 *       {@code hash(key) % N}, precisely so adding or removing a shard never re-routes existing keys —
 *       the property that makes the fleet elastic. The chosen shard becomes the process's home for life
 *       (it stays there by construction thereafter).</li>
 * </ol>
 *
 * <p><b>How rule 1 is answered matters more than it looks.</b> With a {@link ProcessPlacementRepository}
 * configured, the candidate shard is <em>claimed</em> synchronously — one atomic statement whose
 * winner and losers all read back the same placement — and the claim is authoritative. Without one,
 * it falls back to looking the key up in the process-index read model, which is what this used to do
 * and which is only safe in a single-cluster deployment: the index is eventually consistent, so
 * across a sharded fleet a redelivery that arrives before the projection catches up is placed a second
 * time, somewhere else. That is why {@link #warnIfPlacementIsMissing()} says so out loud at startup
 * rather than leaving it to be discovered as two processes with one business key.
 *
 * <p>Only top-level creations are placed. A child ({@code parentStepExecutionId != null}) is spawned by
 * a PROCESS step and must stay on its parent's shard, so it goes out on the local upstream unchanged —
 * as does everything when sharding is off ({@code workflow.sharding.enabled=false}) or the registry is
 * empty (a fail-safe: create locally rather than drop the request).
 */
@Component
@Slf4j
public class IngressRouter {

    private final UpstreamEventPublisher upstreamEventPublisher;
    private final IngressPublisher ingressPublisher;
    private final ProcessIndexRepository processIndexRepository;
    private final ShardRegistry shardRegistry;
    private final ObjectProvider<ProcessPlacementRepository> processPlacementRepository;
    private final boolean sharding;
    private final boolean placementRequired;
    private final AtomicInteger cursor = new AtomicInteger();

    public IngressRouter(UpstreamEventPublisher upstreamEventPublisher,
                         IngressPublisher ingressPublisher,
                         ProcessIndexRepository processIndexRepository,
                         ShardRegistry shardRegistry,
                         ObjectProvider<ProcessPlacementRepository> processPlacementRepository,
                         @Value("${workflow.sharding.enabled:false}") boolean sharding,
                         @Value("${workflow.sharding.placement.required:false}") boolean placementRequired) {
        this.upstreamEventPublisher = upstreamEventPublisher;
        this.ingressPublisher = ingressPublisher;
        this.processIndexRepository = processIndexRepository;
        this.shardRegistry = shardRegistry;
        this.processPlacementRepository = processPlacementRepository;
        this.sharding = sharding;
        this.placementRequired = placementRequired;
    }

    /**
     * A sharded fleet without a placement store is one redelivery away from two processes sharing a
     * business key, and nothing downstream would notice. It is a supported configuration — the
     * placement store is opt-in like everything else here — so by default this warns rather than
     * refusing to start, but it warns in the terms of the damage rather than the terms of the setting.
     *
     * <p>A deployment that cannot tolerate that window sets {@code workflow.sharding.placement.required=true}
     * and the same condition becomes fail-fast: the engine refuses to start rather than run in a shape
     * where a redelivery can silently duplicate a process. Off by default so the safe single-cluster
     * case (where the eventually-consistent index is enough) is unaffected.
     */
    @PostConstruct
    void warnIfPlacementIsMissing() {
        if (sharding && processPlacementRepository.getIfAvailable() == null) {
            if (placementRequired) {
                throw new IllegalStateException(
                        "Sharding is on and workflow.sharding.placement.required=true, but no placement "
                        + "store is configured (workflow.sharding.placement.datasource.url). Refusing to "
                        + "start: without it, a creation redelivered before the process-index projection "
                        + "catches up can be placed on a second shard, leaving two processes for one "
                        + "business key. Configure the placement store or unset placement.required.");
            }
            log.warn("Sharding is on but no placement store is configured "
                    + "(workflow.sharding.placement.datasource.url). New processes will be placed "
                    + "from the process-index read model, which is eventually consistent: a creation "
                    + "redelivered before the projection catches up can be placed on a SECOND shard, "
                    + "leaving two processes for one business key. Configure the placement store, set "
                    + "workflow.sharding.placement.required=true to fail fast instead, or make sure every "
                    + "creation is de-duplicated before it reaches the engine.");
        }
    }

    public void route(ProcessCreationRequested creation) {
        if (!sharding || creation.parentStepExecutionId() != null) {
            upstreamEventPublisher.publish(creation);
            return;
        }
        var shardId = shardFor(creation.businessKey());
        if (shardId == null) {
            // No active shard to place it on — create locally rather than lose the request.
            upstreamEventPublisher.publish(creation);
            return;
        }
        ingressPublisher.publishToShard(creation, shardId);
    }

    private String shardFor(String businessKey) {
        var keyed = businessKey != null && !businessKey.isBlank();
        var placement = processPlacementRepository.getIfAvailable();

        if (keyed && placement != null) {
            var candidate = nextActiveShard();
            // No active shard: fall through to the local upstream rather than claiming a placement on
            // a shard that is not accepting work. Claiming would pin the key to it for good.
            return candidate == null ? null : placement.claim(businessKey, candidate);
        }
        if (keyed) {
            var existing = processIndexRepository.findByBusinessKey(businessKey)
                    .map(ProcessIndexRow::shardId).orElse(null);
            if (existing != null && !existing.isBlank()) {
                return existing;
            }
        }
        return nextActiveShard();
    }

    private String nextActiveShard() {
        List<String> shards = shardRegistry.activeShards();
        if (shards.isEmpty()) {
            return null;
        }
        return shards.get(Math.floorMod(cursor.getAndIncrement(), shards.size()));
    }
}
