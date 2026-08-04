package io.mateu.workflow.domain.aggregates;

/**
 * One incoming link of a step: the step that must complete, and — optionally — the condition under
 * which completing it counts.
 *
 * <p>The guard belongs to the link, not to the step. A step reached from two places can require
 * something different of each: "ship when the payment step completed <em>and</em> the amount is
 * over 100" is a statement about that one route into ship, and saying it on the step instead would
 * apply it to every route, including the ones it was never meant to describe.
 *
 * @param stepId     the step that must have completed
 * @param expression a JEXL expression over the process variables, evaluated when the source step
 *                   has completed. Null or blank means the link has no condition. It is evaluated
 *                   every time eligibility is checked, so a guard that is false now becomes true
 *                   later if the variable it reads changes — which is how a branch that was held
 *                   back gets released.
 */
public record Precondition(String stepId, String expression) {

    public boolean hasGuard() {
        return expression != null && !expression.isBlank();
    }
}
