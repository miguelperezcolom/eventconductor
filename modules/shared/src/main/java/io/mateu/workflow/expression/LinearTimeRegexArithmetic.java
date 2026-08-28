package io.mateu.workflow.expression;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlOperator;

/**
 * The arithmetic every JEXL engine in the product runs with, so {@code =~} cannot be used to burn
 * an orchestration thread.
 *
 * <p>{@link ExpressionGuard} already refuses an expression that is enormous, and the engines
 * refuse one that loops. What neither refuses is an expression that is small, loop-free and still
 * runs for years: {@code 'aaaaaaaaaaaaaaaaaaaaaaaaab' =~ '(a+)+$'} is 40 characters of
 * catastrophic backtracking in {@code java.util.regex}. Since a workflow definition and a rule
 * catalogue are both untrusted input — they arrive from a git import or an editor — that is a
 * denial of service anyone who can write a definition can reach.
 *
 * <p>So the match runs on RE2, which has no backtracking and is linear in the length of the input
 * by construction. The pattern above returns false immediately instead of never.
 *
 * <h2>The operator keeps its meaning</h2>
 *
 * <p>Only the engine changes. In JEXL, {@code =~} against a pattern is an <em>anchored,
 * whole-string</em> match, because its own {@code contains} calls {@code Matcher.matches()}:
 * {@code 'hello world' =~ 'world'} is false, and only {@code '.*world.*'} makes it true. RE2J
 * offers {@code matches()} and {@code find()}, and reaching for the latter here would quietly
 * turn every guard in every existing definition into a substring test — and invert the ones
 * written with {@code !~}, which is what decides whether a step runs.
 *
 * <h2>What RE2 will not accept</h2>
 *
 * <p>The linear-time guarantee is bought by dropping the constructs that make backtracking
 * necessary at all: <b>lookahead and lookbehind</b> ({@code (?=…)}, {@code (?!…)}, {@code (?<=…)})
 * and <b>backreferences</b> ({@code \1}). A pattern using one is refused, with a message that says
 * which construct and why rather than the bare "invalid pattern" that would send someone looking
 * for a typo in a pattern that is perfectly valid Java.
 *
 * <p>Refusing is deliberate, and the alternative was considered: falling back to
 * {@code java.util.regex} for those patterns would hand the ReDoS vector straight back, since a
 * hostile definition would simply include a lookahead to opt out of the protection. Bounding that
 * fallback with a timeout means a watchdog thread per evaluation on the hot path. Neither is worth
 * it for constructs no guard we have ever written uses — and a lookahead can nearly always be
 * rewritten as a plain alternation.
 *
 * <p>The refusal is an ordinary exception, so it meets the callers' existing fail-closed handling:
 * the guard does not evaluate, and the step does not run.
 */
public class LinearTimeRegexArithmetic extends JexlArithmetic {

    public LinearTimeRegexArithmetic(boolean strict) {
        super(strict);
    }

    /**
     * The operator behind {@code =~} and {@code !~}.
     *
     * <p>JEXL calls this with the operands swapped from how they are written: {@code left =~ right}
     * arrives as {@code contains(right, left)}, so the pattern is the container.
     */
    @Override
    public Boolean contains(Object container, Object value) {
        if (container == null || value == null) {
            return false;
        }
        if (container instanceof java.util.regex.Pattern pattern) {
            return matches(pattern.pattern(), value);
        }
        if (container instanceof CharSequence pattern) {
            // CharSequence, not String: this is the type JEXL's own implementation tests for, and
            // a guard that builds its pattern by concatenation hands us something else.
            return matches(pattern.toString(), value);
        }
        // Not a regex at all — membership in a collection, a map or an array. Left to JEXL, which
        // is the only thing that knows what its own operator means for those.
        return super.contains(container, value);
    }

    private Boolean matches(String pattern, Object value) {
        try {
            return Pattern.compile(pattern).matcher(value.toString()).matches();
        } catch (PatternSyntaxException e) {
            throw new UnsupportedRegexException(pattern, e);
        }
    }

    /**
     * A pattern RE2 will not run. Extends {@link RuntimeException} on purpose: every call site
     * fails closed on {@code catch (Exception)}, so a definition with an unsupported pattern
     * stops its own step rather than the engine.
     */
    /**
     * A null operand is false, not an error — this is what {@code &&} and {@code ||} consult, and
     * it is one of the two halves that make an undefined variable falsy.
     */
    @Override
    public boolean toBoolean(Object val) {
        return val != null && super.toBoolean(val);
    }

    /**
     * The other half: the operators that only need a truth value tolerate a null operand, and
     * every other operator does not.
     *
     * <p>An undefined variable used to throw, so both {@code x} and {@code !x} were false at once
     * and a two-way branch had no eligible side — the process stopped dead rather than taking the
     * negative branch. Fixing that is one line on the engine ({@code strict(false)}) plus this,
     * and this is the part that is easy to get wrong. Measured on JEXL 3.7.0:
     *
     * <pre>
     *   strict=true,  arithmetic=true   missing -&gt; THROWS   !missing -&gt; THROWS
     *   strict=false, arithmetic=true   missing -&gt; null     !missing -&gt; THROWS
     *   strict=false, arithmetic=false  missing -&gt; null     !missing -&gt; true, and amount/0 -&gt; 0.0
     * </pre>
     *
     * The middle row is why {@code strict(false)} alone fixes nothing: the negation still throws,
     * which is exactly the case that strands a process. The last row is why the arithmetic must not
     * simply be relaxed: JEXL then swallows every arithmetic error, and no override puts that back
     * because the interpreter catches what {@code divide} throws before the caller sees it.
     *
     * <p>Overriding {@code not} or {@code controlNullOperand} does not work either — the
     * interpreter asks {@code isStrict(operator)} before it dispatches, so neither is reached.
     *
     * <p>So comparison and negation go quiet on null, and arithmetic stays loud. Which means
     * {@code amount + missing} still fails, deliberately: JavaScript would hand you {@code NaN} and
     * route the process on it, and {@code amount / 0} still fails, which HARD-RULE-08 requires.
     */
    @Override
    public boolean isStrict(JexlOperator operator) {
        return switch (operator) {
            case NOT, AND, OR, EQ, EQSTRICT, LT, LTE, GT, GTE, CONDITION, EMPTY, SIZE -> false;
            default -> super.isStrict(operator);
        };
    }

    public static class UnsupportedRegexException extends RuntimeException {

        UnsupportedRegexException(String pattern, Throwable cause) {
            super(explain(pattern), cause);
        }

        private static String explain(String pattern) {
            var reason = usesLookaround(pattern)
                    ? " It uses lookahead or lookbehind, which RE2 does not support: the engine"
                    + " matches in linear time, and that is what pays for it. An alternation or an"
                    + " explicit '.*' can usually say the same thing."
                    : usesBackreference(pattern)
                    ? " It uses a backreference, which RE2 does not support: the engine matches in"
                    + " linear time, and that is what pays for it."
                    : " Check it against RE2 syntax, which is a subset of Java's.";
            return "The regular expression '" + pattern + "' cannot be evaluated." + reason;
        }

        private static boolean usesLookaround(String pattern) {
            return pattern.contains("(?=") || pattern.contains("(?!")
                    || pattern.contains("(?<=") || pattern.contains("(?<!");
        }

        private static boolean usesBackreference(String pattern) {
            for (var i = 0; i < pattern.length() - 1; i++) {
                if (pattern.charAt(i) == '\\' && Character.isDigit(pattern.charAt(i + 1))) {
                    return true;
                }
            }
            return false;
        }
    }
}
