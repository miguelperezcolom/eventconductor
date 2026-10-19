package io.mateu.workflow.domain.batch;

/**
 * What a batch operation does to each process its filter matches — one of the operator commands, applied
 * one process at a time through the same door a single command takes ({@code ProcessOperationsService}):
 * the flow-authorization check, the status rule of the use case ({@code acceptsFrom}), and the use case
 * itself on the node that owns the process, under its lock.
 *
 * <p>Designed to grow: an operation that needs arguments — {@link #SET_VARIABLES}, {@link #RETRY_FROM_STEP},
 * later {@code MIGRATE} (to a definition version) — carries them in the batch's {@code parameters}, and
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
    RESUME,
    /**
     * Change the variables of each live (RUNNING, PAUSED or ERROR) process — the manual operation, with
     * its reason, audited with the batch's creator as the operator. Parameters: {@code reason},
     * {@code set.<name>} (value) and {@code remove.<name>}.
     */
    SET_VARIABLES,
    /**
     * Run each live process again from one step — the manual operation, audited the same way.
     * Parameters: {@code stepId}, {@code reason}.
     */
    RETRY_FROM_STEP;

    /** The parameter every manual-operation type requires, and an incident retry carries. */
    public static final String REASON = "reason";
    /** RETRY_FROM_STEP: the step to run again from. */
    public static final String STEP_ID = "stepId";
    /** SET_VARIABLES: prefix of a variable to set ({@code set.amount = 12}). */
    public static final String SET_PREFIX = "set.";
    /** SET_VARIABLES: prefix of a variable to remove ({@code remove.tmp}). */
    public static final String REMOVE_PREFIX = "remove.";

    /** Whether this type is one of the audited manual operations, which require a reason. */
    public boolean isManualOperation() {
        return this == SET_VARIABLES || this == RETRY_FROM_STEP;
    }
}
