package io.mateu.workflow.application.out;

import io.mateu.workflow.dtos.events.integration.MessageReceived;

/**
 * Publishes a {@link MessageReceived} to the <b>shared messages topic</b> — the one channel every
 * shard consumes — so a {@code SEND_MESSAGE} on one shard can wake a {@code WAIT_FOR_MESSAGE} on any
 * other. A message's target shard is not derivable from the sender (elastic sharding has no fixed
 * shard count), so the message is broadcast and each shard correlates it against its own waiting
 * steps; non-owners match nothing and drop it — the existing fail-closed contract.
 *
 * <p>Distinct from {@link UpstreamEventPublisher}, which addresses one shard's own {@code upstream}.
 * Which of the two a send uses is decided by {@code MessageDispatcher} from
 * {@code workflow.messages.shared-topic}; with the read model / sharding off, messages keep going
 * through {@code upstream} exactly as before and this port is unused.
 */
public interface MessagePublisher {

    void publish(MessageReceived message);
}
