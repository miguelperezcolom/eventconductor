package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * How a {@link StepType#JOIN} merges its incoming branches:
 * <ul>
 *   <li>{@code AND} — a parallel/synchronizing join: waits for <em>all</em> incoming branches
 *       to complete (the default, and the historical behaviour of any multi-input step).</li>
 *   <li>{@code XOR} — an exclusive join: proceeds as soon as <em>any one</em> incoming branch
 *       completes.</li>
 * </ul>
 * Only meaningful on JOIN steps; ignored elsewhere.
 */
public enum JoinType {
    AND,
    XOR;

    /** Absent / blank / unknown maps to {@code AND} so old definitions keep their semantics. */
    @JsonCreator
    public static JoinType fromJson(String value) {
        if (value == null || value.isBlank()) return AND;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AND;
        }
    }
}
