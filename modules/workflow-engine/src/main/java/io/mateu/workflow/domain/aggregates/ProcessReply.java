package io.mateu.workflow.domain.aggregates;

import java.time.LocalDateTime;

/**
 * The answer a process gave to whoever invoked it synchronously — recorded once, on the process
 * itself, in the same transaction as the transition that produced it.
 *
 * <p>Most replies come from a {@link StepType#REPLY} step ({@link Outcome#REPLIED}). The others are
 * what the engine answers on the process's behalf when it reaches an end without having replied:
 * a failure (with or without a compensation under way), a cancellation, or a completion along a
 * path that had no REPLY step.
 *
 * @param stepId       the REPLY step that produced it; null for an engine-produced outcome
 * @param payload      the reply as JSON; null when there is none (every outcome but REPLIED)
 * @param outcome      what kind of answer this is
 * @param compensation whether a saga rollback was involved, and how far it got
 * @param error        a one-line summary of the failure (failed step and message); null otherwise
 * @param repliedAt    when the reply was recorded
 */
public record ProcessReply(
        String stepId,
        String payload,
        Outcome outcome,
        Compensation compensation,
        String error,
        LocalDateTime repliedAt
) {

    public ProcessReply {
        compensation = compensation == null ? Compensation.NONE : compensation;
    }

    /** A REPLY step's answer. */
    public static ProcessReply replied(String stepId, String payload) {
        return new ProcessReply(stepId, payload, Outcome.REPLIED, Compensation.NONE, null, LocalDateTime.now());
    }

    /** An answer the engine gives on the process's behalf. */
    public static ProcessReply of(Outcome outcome, Compensation compensation, String error) {
        return new ProcessReply(null, null, outcome, compensation, error, LocalDateTime.now());
    }

    /** True for every outcome in which the process did not get to answer successfully. */
    public boolean isFailure() {
        return outcome != Outcome.REPLIED && outcome != Outcome.COMPLETED_WITHOUT_REPLY;
    }

    public enum Outcome {
        /** A REPLY step answered. */
        REPLIED,
        /** A step failed before any REPLY; see {@link #compensation} for the rollback. */
        FAILED,
        /** A step failed before any REPLY and the saga rollback completed. */
        COMPENSATED,
        /** A step failed before any REPLY and the saga rollback itself failed part-way. */
        COMPENSATION_FAILED,
        /** The process was cancelled before it replied. */
        CANCELLED,
        /** The process completed along a path with no REPLY step. */
        COMPLETED_WITHOUT_REPLY
    }

    public enum Compensation {
        /** No rollback involved: nothing to undo, or no failure at all. */
        NONE,
        /** The reply was given as soon as the process failed; the rollback is still running. */
        IN_PROGRESS,
        /** The rollback ran to the end. */
        DONE,
        /** The rollback stopped part-way: a compensation step failed. */
        FAILED
    }
}
