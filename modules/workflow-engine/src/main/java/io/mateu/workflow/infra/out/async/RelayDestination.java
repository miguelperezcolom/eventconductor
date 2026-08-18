package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.dtos.events.integration.MessageReceived;

/**
 * Which channel a relayed outbox event goes out on.
 *
 * <p>Almost everything goes to this shard's own {@code outbox}, which is the whole of the default,
 * single-cluster behaviour. Two events leave it, and both for the same reason — their consumer is not
 * this shard:
 *
 * <ul>
 *   <li>a {@link MessageReceived} when cross-shard messaging is on, so it can wake a
 *       {@code WAIT_FOR_MESSAGE} on any shard;</li>
 *   <li>a {@link ProcessStatusChanged} in remote projection mode, so the standalone projector
 *       maintains one fleet-wide index. <b>Diverted, not duplicated</b>: a second copy in this shard's
 *       own database would be a partial index that looks like a complete one, which is the most
 *       expensive kind of wrong. One index, one writer.</li>
 * </ul>
 *
 * <p>Its own class, and not a private method on the relay, because these three lines decide where
 * every state transition in the fleet ends up and the relay is a thread that cannot be unit-tested.
 */
final class RelayDestination {

    static final String OUTBOX = "outbox";
    static final String MESSAGES = "messages";
    static final String PROCESS_INDEX = "processIndex";

    private RelayDestination() {
    }

    static String bindingFor(DomainEvent event, boolean sharedMessages, boolean remoteProjection) {
        if (sharedMessages && event instanceof MessageReceived) {
            return MESSAGES;
        }
        if (remoteProjection && event instanceof ProcessStatusChanged) {
            return PROCESS_INDEX;
        }
        return OUTBOX;
    }
}
