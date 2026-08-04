package io.mateu.workflow.domain.aggregates;

/**
 * Whether a workflow definition is open for business — one answer, not two booleans.
 *
 * <p>It was `disabled` and `archived`, which between them can say four things when only three of
 * them mean anything, and left "is an archived workflow also disabled?" to be settled in prose.
 * Ordered by severity, so combining what the file declares with what an operator has done is
 * taking the stricter of the two.
 *
 * <p>Not to be confused with pausing, which is a different axis and stays a flag of its own: a
 * paused workflow still accepts new instances (they are born paused), a disabled one accepts none.
 * A workflow can be both.
 */
public enum WorkflowStatus {

    /** Accepts new instances. */
    ACTIVE,

    /** Accepts no new instances, cron included. Still listed, and its processes still run. */
    DISABLED,

    /** Retired: as disabled, and hidden from the listing. */
    ARCHIVED;

    /** The stricter of the two — how a declaration and a runtime decision combine. */
    public WorkflowStatus and(WorkflowStatus other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }

    public boolean accceptsNewInstances() {
        return this == ACTIVE;
    }

    /** Reads what a definition file says, tolerating the older booleans and an unknown word. */
    public static WorkflowStatus of(String declared, boolean disabled, boolean archived) {
        if (declared != null && !declared.isBlank()) {
            for (var candidate : values()) {
                if (candidate.name().equalsIgnoreCase(declared.trim())) {
                    return candidate;
                }
            }
        }
        if (archived) {
            return ARCHIVED;
        }
        return disabled ? DISABLED : ACTIVE;
    }
}
