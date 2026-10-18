package io.mateu.workflow.domain.services;

/**
 * Whether a condition's result lets the flow through — the one rule every guard, loop condition and
 * completion condition in the engine reads by.
 *
 * <p>Close to JavaScript, with one deliberate departure that must not be "corrected": the string
 * {@code "false"} is falsy here and truthy in JavaScript. Every process variable in this engine is a
 * {@code (name, value)} pair of <em>strings</em>, so a variable holding {@code "false"} is what a
 * guard written as {@code !approved} reads — treating it as truthy the way JavaScript does would
 * silently invert every negated guard in every deployed definition.
 *
 * <p>Numbers follow JavaScript: {@code 0} is falsy, anything else is not.
 */
public final class Truthiness {

    public static boolean isTruthy(Object result) {
        if (result == null) {
            return false;
        }
        if (result instanceof Boolean b) {
            return b;
        }
        if (result instanceof String s) {
            return !s.isEmpty() && !"false".equals(s);
        }
        if (result instanceof Number n) {
            return n.doubleValue() != 0d && !Double.isNaN(n.doubleValue());
        }
        return true;
    }

    private Truthiness() {
    }
}
