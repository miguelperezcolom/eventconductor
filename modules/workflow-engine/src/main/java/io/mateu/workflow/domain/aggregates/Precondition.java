package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One incoming link of a step: the step that must complete, and — optionally — the condition under
 * which completing it counts.
 *
 * <p>The guard belongs to the link, not to the step. A step reached from two places can require
 * something different of each: "ship when the payment step completed <em>and</em> the amount is
 * over 100" is a statement about that one route into ship, and saying it on the step instead would
 * apply it to every route, including the ones it was never meant to describe. A step-level
 * {@code preconditionExpression} is the special case where every route asks the same thing, and
 * {@link Step#resolvedPreconditions()} folds it into these links so a guard has one home.
 *
 * @param stepId     the step that must have completed
 * @param expression a JEXL expression over the process variables, evaluated when the source step
 *                   has completed. Null or blank means the link has no condition. It is evaluated
 *                   every time eligibility is checked, so a guard that is false now becomes true
 *                   later if the variable it reads changes — which is how a branch that was held
 *                   back gets released.
 * @param onFalse    what a false guard means: {@link GuardMode#WAIT} (the default) holds the step;
 *                   {@link GuardMode#DISCARD} treats the route as not taken, so the process may
 *                   complete around it. Null reads as WAIT.
 */
public record Precondition(String stepId, String expression,
                           @JsonInclude(value = JsonInclude.Include.CUSTOM,
                                   valueFilter = GuardMode.WaitIsTheDefault.class)
                           GuardMode onFalse) {

    public Precondition {
        onFalse = onFalse == null ? GuardMode.WAIT : onFalse;
    }

    /** A link whose false guard holds the step — the shape every link had before modes existed. */
    public Precondition(String stepId, String expression) {
        this(stepId, expression, GuardMode.WAIT);
    }

    public boolean hasGuard() {
        return expression != null && !expression.isBlank();
    }

    /** True when this link, unsatisfied, is a step waiting rather than a branch not taken. */
    public boolean holdsWhenFalse() {
        return hasGuard() && GuardMode.WAIT.equals(onFalse);
    }
}
