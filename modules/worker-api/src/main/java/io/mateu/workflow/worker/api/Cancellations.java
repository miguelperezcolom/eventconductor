package io.mateu.workflow.worker.api;

/**
 * The runtime's view of cancellations, abstracted from how they arrive. The Kafka adapter backs it
 * with {@code CancelledTasks} (cancellations ride the inbound topic); the embedded adapter — which
 * the engine never sends cancellations to — backs it with a no-op that is never cancelled.
 */
public interface Cancellations {

    /**
     * Whether this task was cancelled, <b>consuming</b> the record so it fires once. The dispatcher
     * calls it before starting and before replying.
     */
    boolean claim(String taskExecutionId);

    /** Whether this task is cancelled, <b>without</b> consuming — for {@code TaskContext.isCancelled()}. */
    boolean isCancelled(String taskExecutionId);

    /** A no-op that is never cancelled — the embedded adapter's default. */
    Cancellations NONE = new Cancellations() {
        public boolean claim(String taskExecutionId) {
            return false;
        }

        public boolean isCancelled(String taskExecutionId) {
            return false;
        }
    };
}
