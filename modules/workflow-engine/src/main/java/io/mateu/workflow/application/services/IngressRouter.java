package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ShardRegistry;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Places a new process on a shard — the one point in elastic sharding where a shard is <b>chosen</b>.
 *
 * <p>Two rules, in order:
 * <ol>
 *   <li><b>Idempotency wins.</b> If a process with this business key already exists, route to the shard
 *       it already lives on ({@code shard_id} from the read model). A retry, a redelivery, or cron's
 *       deterministic per-occurrence key must land back on the same shard, or the per-shard creation
 *       idempotency guard cannot collapse the duplicate and the fleet grows two processes for one key.</li>
 *   <li><b>New keys spread.</b> Otherwise pick an {@code active} shard round-robin. Round-robin, not
 *       {@code hash(key) % N}, precisely so adding or removing a shard never re-routes existing keys —
 *       the property that makes the fleet elastic. The chosen shard becomes the process's home for life
 *       (it stays there by construction thereafter).</li>
 * </ol>
 *
 * <p>Only top-level creations are placed. A child ({@code parentStepExecutionId != null}) is spawned by
 * a PROCESS step and must stay on its parent's shard, so it goes out on the local upstream unchanged —
 * as does everything when sharding is off ({@code workflow.sharding.enabled=false}) or the registry is
 * empty (a fail-safe: create locally rather than drop the request).
 */
@Component
public class IngressRouter {

    private final UpstreamEventPublisher upstreamEventPublisher;
    private final IngressPublisher ingressPublisher;
    private final ProcessIndexRepository processIndexRepository;
    private final ShardRegistry shardRegistry;
    private final boolean sharding;
    private final AtomicInteger cursor = new AtomicInteger();

    public IngressRouter(UpstreamEventPublisher upstreamEventPublisher,
                         IngressPublisher ingressPublisher,
                         ProcessIndexRepository processIndexRepository,
                         ShardRegistry shardRegistry,
                         @Value("${workflow.sharding.enabled:false}") boolean sharding) {
        this.upstreamEventPublisher = upstreamEventPublisher;
        this.ingressPublisher = ingressPublisher;
        this.processIndexRepository = processIndexRepository;
        this.shardRegistry = shardRegistry;
        this.sharding = sharding;
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
        if (businessKey != null && !businessKey.isBlank()) {
            var existing = processIndexRepository.findByBusinessKey(businessKey)
                    .map(ProcessIndexRow::shardId).orElse(null);
            if (existing != null && !existing.isBlank()) {
                return existing;
            }
        }
        List<String> shards = shardRegistry.activeShards();
        if (shards.isEmpty()) {
            return null;
        }
        return shards.get(Math.floorMod(cursor.getAndIncrement(), shards.size()));
    }
}
