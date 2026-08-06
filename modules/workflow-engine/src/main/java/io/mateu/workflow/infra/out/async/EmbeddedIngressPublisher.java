package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded (single-node) ingress sink: one shard, so a chosen shard is always this one — delegate to
 * the local upstream publisher.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddedIngressPublisher implements IngressPublisher {

    private final UpstreamEventPublisher upstreamEventPublisher;

    @Override
    public void publishToShard(DomainEvent event, String shardId) {
        upstreamEventPublisher.publish(event);
    }
}
