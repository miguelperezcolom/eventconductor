package io.mateu.workflow.application.services;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;

import java.util.Map;

/**
 * JEXL wrapper for rule expressions. Same engine and semantics as the
 * workflow-engine step precondition evaluator, but restricted to Map facts so
 * the runtime stays free of UI framework dependencies.
 */
public class RuleExpressionEvaluator {

    private final JexlEngine jexl = new JexlBuilder()
            .cache(512)
            .strict(true)
            .create();

    public Object eval(String expression, Map<String, Object> facts) {
        JexlContext context = new MapContext();
        if (facts != null) {
            facts.forEach(context::set);
        }
        return jexl.createExpression(expression).evaluate(context);
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
        jexl.createExpression(expression);
    }
}
