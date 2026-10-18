package io.mateu.workflow.domain.batch;

/**
 * What a batch operation does to each process its filter matches — one of the operator commands, applied
 * one process at a time through the same door a single command takes ({@code ProcessOperationsService}):
 * the flow-authorization check, the status rule of the use case ({@code acceptsFrom}), and the use case
 * itself on the node that owns the process, under its lock.
 *
 * <p>Designed to grow: an operation that needs arguments — {@code MIGRATE} (to a definition version),
 * {@code SET_VARIABLES}, {@code RETRY_FROM_STEP} — carries them in the batch's {@code parameters}, and
 * plugs in as one more entry of {@code BatchOperationService}'s action table. Nothing else changes: the
 * filter, the walk, the counters, the throttle and the resumption are the same for every type.
 */
public enum BatchOperationType {
    /** Cancel each process (not a COMPLETED one). */
    CANCEL,
    /** Pick each stopped (ERROR or CANCELLED) process up where it stopped. */
    RETRY,
    /** Run each stopped (ERROR or CANCELLED) process again from the beginning. */
    RESTART,
    /** Pause each PENDING or RUNNING process. */
    PAUSE,
    /** Resume each PAUSED process. */
    RESUME
}
