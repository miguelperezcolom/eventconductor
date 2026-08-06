package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides where an externally-injected {@link MessageReceived} goes: the shared {@code messages}
 * topic (so it can wake a waiter on any shard) when {@code workflow.messages.shared-topic} is on, or
 * — the default — one shard's own {@code upstream}, exactly as before. The one place the sharded vs
 * single-cluster message routing is chosen, so the external entry points (the MCP tool, the REST
 * endpoint) do not each have to know about sharding.
 *
 * <p>Only externally-injected messages come through here. A {@code SEND_MESSAGE} step's message rides
 * the process outbox and is routed at the outbox relay by the same flag, because a domain aggregate
 * cannot reach an application publisher.
 */
@Component
public class MessageDispatcher {

    private final UpstreamEventPublisher upstreamEventPublisher;
    private final MessagePublisher messagePublisher;
    private final boolean sharedTopic;

    public MessageDispatcher(UpstreamEventPublisher upstreamEventPublisher,
                             MessagePublisher messagePublisher,
                             @Value("${workflow.messages.shared-topic:false}") boolean sharedTopic) {
        this.upstreamEventPublisher = upstreamEventPublisher;
        this.messagePublisher = messagePublisher;
        this.sharedTopic = sharedTopic;
    }

    public void dispatch(MessageReceived message) {
        if (sharedTopic) {
            messagePublisher.publish(message);
        } else {
            upstreamEventPublisher.publish(message);
        }
    }
}
