package io.mateu.workflow.domain.aggregates;

import java.util.Set;

/**
 * Whether a start may reuse a business key a process of the same definition already holds — Temporal's
 * {@code WorkflowIdReusePolicy}. Declared on the definition as {@code businessKeyPolicy}; applied in
 * {@code CreateProcessUseCase}, so every start path honours it. See FLOW-CONTROL-PLAN.md §4.2.
 */
public enum BusinessKeyPolicy {

    /** A key names one process for ever: a second start collapses into it (the behaviour before 2.33). */
    UNIQUE("unique"),
    /** A new run may take the key once the holder has ended, whatever the outcome. */
    ALLOW_AFTER_FINISHED("allowAfterFinished"),
    /** A new run may take the key once the holder has ended other than COMPLETED. */
    ALLOW_AFTER_FAILED("allowAfterFailed"),
    /** A running holder is cancelled and a new run takes the key; an ended one simply hands it over. */
    TERMINATE_RUNNING("terminateRunning");

    private static final Set<ProcessStatus> ENDED = Set.of(ProcessStatus.COMPLETED, ProcessStatus.ERROR,
            ProcessStatus.CANCELLED, ProcessStatus.COMPENSATED, ProcessStatus.COMPENSATION_FAILED);

    private final String text;

    BusinessKeyPolicy(String text) {
        this.text = text;
    }

    /** As the file writes it. */
    public String text() {
        return text;
    }

    /** The policy a file value names; null and blank mean {@link #UNIQUE}. @throws IllegalArgumentException if unknown */
    public static BusinessKeyPolicy of(String value) {
        if (value == null || value.isBlank()) {
            return UNIQUE;
        }
        for (var policy : values()) {
            if (policy.text.equals(value.trim())) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown businessKeyPolicy '" + value + "'");
    }

    /** What a start does about the process that holds its key. */
    public enum Decision {
        /** Collapse into the holder: nothing is started. */
        KEEP,
        /** Start a new run, which takes the key over from the holder. */
        REUSE,
        /** Cancel the holder, then start a new run which takes the key over. */
        TERMINATE_AND_REUSE
    }

    /** What this policy decides about a holder of the same definition in {@code status}. */
    public Decision decide(ProcessStatus status) {
        var ended = status != null && ENDED.contains(status);
        return switch (this) {
            case UNIQUE -> Decision.KEEP;
            case ALLOW_AFTER_FINISHED -> ended ? Decision.REUSE : Decision.KEEP;
            case ALLOW_AFTER_FAILED -> ended && status != ProcessStatus.COMPLETED ? Decision.REUSE : Decision.KEEP;
            case TERMINATE_RUNNING -> ended ? Decision.REUSE : Decision.TERMINATE_AND_REUSE;
        };
    }

    /** Whether a process in this status has ended (no step of it will run again on its own). */
    public static boolean ended(ProcessStatus status) {
        return status != null && ENDED.contains(status);
    }
}
