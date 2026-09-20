package io.mateu.workflow.application.out;

import io.mateu.workflow.application.readmodel.MessageSubscription;
import java.util.List;

/**
 * The shared record of which shard has a step waiting for a message (layer 2 of sharded message
 * routing). Written by the projector as steps start and stop waiting; read by the router to send a
 * message only to the shards that can correlate it. A port, so it lives in the engine's own database
 * (embedded) or the read database a fanned-out projector owns (remote), like the process index.
 *
 * <p>Idempotent: keyed by step execution, so a redelivered start upserts the same row and a stop
 * deletes by that id whether or not it was there.
 */
public interface MessageSubscriptionRepository {

    /** A step started waiting (or its correlation key changed): insert or update its subscription. */
    void subscribe(MessageSubscription subscription);

    /** A step stopped waiting (completed, cancelled, timed out, or its process ended): remove it. */
    void unsubscribe(String stepExecutionId);

    /** The distinct shards with a step waiting for this message and key — the routing answer. */
    List<String> shardsWaitingFor(String messageName, String correlationKey);
}
