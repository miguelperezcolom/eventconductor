package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * What a false guard on an incoming link does to the step it guards:
 * <ul>
 *   <li>{@code WAIT} — the link is simply not satisfied, and the step waits. Guards read process
 *       variables, so one that is false now becomes true later if the variable changes; the step
 *       is released then. The process is not wrapped up around a step waiting this way.</li>
 *   <li>{@code DISCARD} — the route is not taken, and the step is a branch the flow did not go
 *       down. Nothing is waiting for it, so the process may complete around it and cancel it,
 *       exactly as it does for the branch a CHOICE did not pick.</li>
 * </ul>
 *
 * <p>Which of the two you mean used to be decided by <em>where</em> you wrote the condition — a
 * step-level {@code preconditionExpression} discarded, a guard on a link waited — which is a
 * strange thing for the spelling to decide. It is a property of the condition, and this is where
 * it lives now.
 */
public enum GuardMode {
    WAIT,
    DISCARD;

    /**
     * Keeps {@code WAIT} out of serialized definitions: a definition comes back out spelled the
     * way it went in, rather than with the default written onto every link that never asked for
     * it. {@code DISCARD} is written, since there it is the author's word — or the mark of a link
     * {@link Step#resolvedPreconditions()} folded a step-level expression into, and those are
     * computed, never persisted.
     */
    public static final class WaitIsTheDefault {
        @Override
        public boolean equals(Object other) {
            return other == null || WAIT.equals(other);
        }

        @Override
        public int hashCode() {
            return WAIT.hashCode();
        }
    }

    /** Absent / blank / unknown maps to {@code WAIT}, the behaviour a link guard has always had. */
    @JsonCreator
    public static GuardMode fromJson(String value) {
        if (value == null || value.isBlank()) return WAIT;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WAIT;
        }
    }
}
