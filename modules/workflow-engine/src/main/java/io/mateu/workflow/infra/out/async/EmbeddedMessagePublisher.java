package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded (single-node) message sink. Sharding is a distributed-topology concept, so here the
 * "shared messages topic" and the node's own upstream are the same reach: delegate to the upstream
 * publisher, which correlates locally. This keeps {@code workflow.messages.shared-topic=true} working
 * in embedded mode (and in tests) without a second correlation path to maintain.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddedMessagePublisher implements MessagePublisher {

    private final UpstreamEventPublisher upstreamEventPublisher;

    @Override
    public void publish(MessageReceived message) {
        upstreamEventPublisher.publish(message);
    }
}
