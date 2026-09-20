package io.mateu.workflow.application.readmodel;

import java.time.LocalDateTime;

/**
 * One step waiting for a message: which shard it is on and the {@code (messageName, correlationKey)}
 * it will correlate. Keyed by the step execution, so projecting the same start twice is a no-op and
 * a stop is a delete by that id. The routing half of the fleet database (layer 2).
 */
public record MessageSubscription(
        String stepExecutionId,
        String messageName,
        String correlationKey,
        String shardId,
        LocalDateTime updatedAt) {
}
