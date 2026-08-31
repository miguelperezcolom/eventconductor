package io.mateu.workflow.application.services;

import io.mateu.workflow.expression.ExpressionGuard;
import io.mateu.workflow.expression.LinearTimeRegexArithmetic;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import java.util.Map;

/**
 * JEXL wrapper for rule expressions. Same engine and semantics as the
 * workflow-engine step precondition evaluator, but restricted to Map facts so
 * the runtime stays free of UI framework dependencies.
 */
public class RuleExpressionEvaluator {

    /**
     * The same denial list the workflow guards carry, for the same reason: a rule expression is
     * read from a rule catalogue that a git import or the rule editor writes, so it is untrusted
     * input, and a rule evaluates on the same threads a workflow does. See
     * {@code JEXLEvaluator.GUARD_FEATURES}.
     */
    private static final JexlFeatures RULE_FEATURES = new JexlFeatures()
            .loops(false)
            .lambda(false)
            .newInstance(false)
            .script(false)
            .annotation(false)
            .pragma(false)
            .sideEffectGlobal(false);

    // Rule expressions come from rule definitions, which may be imported from git or edited in
    // the UI — treat them as untrusted. RESTRICTED blocks reflection, System, Runtime, etc.,
    // matching the workflow-engine JEXLEvaluator so a rule cannot escalate to RCE.
    //
    // The arithmetic is the same one the workflow guards run with, and for the same reason: it is
    // what stops `facts =~ '(a+)+$'` from pinning the thread this rule evaluates on. A rule
    // catalogue is written by the same git import and the same editor a workflow definition is.
    private final JexlEngine jexl = new JexlBuilder()
            .arithmetic(new LinearTimeRegexArithmetic(true))
            .permissions(JexlPermissions.RESTRICTED)
            .features(RULE_FEATURES)
            .cache(512)
            // Falsy, like the workflow guards and through the same shared arithmetic: a fact
            // that is not in the map reads as false rather than throwing, so a rule that mentions
            // one does not fail — it simply does not match.
            .strict(false)
            .create();

    public Object eval(String expression, Map<String, Object> facts) {
        // Size and nesting before the parser — see ExpressionGuard: an over-nested expression
        // overflows the parser stack, and that Error would pass straight through the callers'
        // fail-closed handling instead of failing just this rule.
        ExpressionGuard.check(expression, "rule");
        return ExpressionGuard.failClosed("rule", () -> {
            JexlContext context = new MapContext();
            if (facts != null) {
                facts.forEach(context::set);
            }
            return jexl.createExpression(expression).evaluate(context);
        });
    }

    /**
     * Truthiness convention shared with workflow step preconditions: a Boolean
     * true, or a non-empty String other than "false".
     */
    public boolean evalPredicate(String expression, Map<String, Object> facts) {
        Object result = eval(expression, facts);
        return result != null && (result instanceof Boolean b && b
                || result instanceof String s && !s.isEmpty() && !"false".equals(s));
    }

    /** Parses the expression, throwing JexlException if it is not valid. */
    public void parse(String expression) {
        ExpressionGuard.check(expression, "rule");
        ExpressionGuard.failClosed("rule", () -> jexl.createExpression(expression));
    }
}
