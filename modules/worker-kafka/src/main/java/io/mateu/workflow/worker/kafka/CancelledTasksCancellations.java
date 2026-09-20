package io.mateu.workflow.worker.kafka;

import io.mateu.workflow.worker.CancelledTasks;
import io.mateu.workflow.worker.api.Cancellations;

/**
 * Backs the runtime's {@link Cancellations} with the engine's {@link CancelledTasks}, over which
 * cancellations arrive on the same inbound topic as the tasks. {@code claim} consumes the record so
 * a cancellation stops the task exactly once; {@code isCancelled} peeks for the handler's poll.
 */
final class CancelledTasksCancellations implements Cancellations {

    private final CancelledTasks cancelledTasks;

    CancelledTasksCancellations(CancelledTasks cancelledTasks) {
        this.cancelledTasks = cancelledTasks;
    }

    @Override
    public boolean claim(String taskExecutionId) {
        return cancelledTasks.claim(taskExecutionId);
    }

    @Override
    public boolean isCancelled(String taskExecutionId) {
        return cancelledTasks.isCancelled(taskExecutionId);
    }
}
