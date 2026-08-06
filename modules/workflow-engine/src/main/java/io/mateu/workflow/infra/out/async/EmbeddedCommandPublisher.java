package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.CommandPublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded (single-node) command sink. There is one shard, so the owning shard is always this one:
 * delegate to the upstream publisher, which correlates locally. Keeps
 * {@code workflow.sharding.enabled=true} working in embedded mode without a second routing path.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddedCommandPublisher implements CommandPublisher {

    private final UpstreamEventPublisher upstreamEventPublisher;

    @Override
    public void publish(DomainEvent command) {
        upstreamEventPublisher.publish(command);
    }
}
