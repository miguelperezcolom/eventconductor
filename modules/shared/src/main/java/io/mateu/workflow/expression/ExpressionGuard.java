package io.mateu.workflow.expression;

/**
 * The size and shape limits every JEXL expression in the product must clear before it is handed
 * to a parser — workflow step guards and link conditions, correlation expressions, and rule
 * expressions alike.
 *
 * <p><b>Why a guard exists at all.</b> The JEXL sandbox ({@code JexlPermissions.RESTRICTED} plus
 * expression — not script — parsing) already denies the two things that would be catastrophic:
 * reflection cannot reach {@code Runtime}, and an expression cannot contain a loop, an assignment
 * or a lambda, so it cannot spin. What it does not deny is an expression that is merely
 * <em>enormous</em>. {@code ((((…1…))))} nested a few thousand deep makes JEXL's recursive-descent
 * parser exhaust the thread stack, and a {@link StackOverflowError} is an {@link Error}: every
 * fail-closed {@code catch (Exception)} around a guard evaluation lets it straight through, and it
 * unwinds the orchestration thread — the Kafka consumer, the timer scheduler — instead of failing
 * the one step whose definition was bad. A workflow definition is untrusted input here: it arrives
 * from a git import or from the definition editor.
 *
 * <p>So the limits are checked by scanning the source, before any parser sees it. The scan is a
 * single pass over the characters and runs on the orchestration hot path, so it stays that cheap.
 *
 * <p>The ceilings are deliberately far above any expression a human writes — a guard that needs
 * more than {@value #MAX_LENGTH} characters or {@value #MAX_NESTING} levels of nesting is not a
 * business rule — and they can be raised per deployment through the system properties named below
 * without a rebuild, for the one installation that proves otherwise.
 */
public final class ExpressionGuard {

    /** Longest accepted expression source, in characters. Override: {@code eventconductor.expression.max-length}. */
    public static final int MAX_LENGTH = Integer.getInteger("eventconductor.expression.max-length", 4096);

    /**
     * Deepest accepted bracket nesting — {@code (}, {@code [} and <code>{</code> count alike, since
     * each one costs the parser a frame. Override: {@code eventconductor.expression.max-nesting}.
     */
    public static final int MAX_NESTING = Integer.getInteger("eventconductor.expression.max-nesting", 64);

    private ExpressionGuard() {
    }

    /**
     * Rejects an expression that is too long or too deeply nested to parse safely.
     *
     * @param expression the expression source; {@code null} and blank pass, because "no expression"
     *                   is every caller's own case to handle and never a hazard
     * @param what       what the expression is, for the message — e.g. {@code "precondition"}
     * @throws ExpressionRejectedException if a limit is exceeded
     */
    public static void check(String expression, String what) {
        if (expression == null) {
            return;
        }
        if (expression.length() > MAX_LENGTH) {
            throw new ExpressionRejectedException(
                    what + " expression is " + expression.length() + " characters long, over the "
                            + MAX_LENGTH + " character limit");
        }
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            switch (expression.charAt(i)) {
                case '(', '[', '{' -> {
                    if (++depth > MAX_NESTING) {
                        throw new ExpressionRejectedException(
                                what + " expression nests brackets more than " + MAX_NESTING
                                        + " deep, which would overflow the parser stack");
                    }
                }
                case ')', ']', '}' -> depth--;
                default -> {
                }
            }
        }
    }

    /**
     * Runs a parse-and-evaluate and converts a stack overflow into an ordinary exception.
     *
     * <p>{@link #check} is the first line and catches the expressions that are obviously oversized;
     * this is the second, for the shape that slips under both ceilings and still exhausts the stack
     * — a long unbracketed chain, say, or a nesting limit raised past what the running JVM's stack
     * can take. Only {@link StackOverflowError} is caught, and only around one evaluation: the
     * stack is fully unwound by the time we are here, so the thread is sound again, and the caller
     * gets the same {@link ExpressionRejectedException} an oversized expression would have raised.
     * Everything else — a {@code JexlException} for a bad reference, any other error — is left to
     * propagate exactly as it did before.
     */
    public static <T> T failClosed(String what, java.util.function.Supplier<T> evaluation) {
        try {
            return evaluation.get();
        } catch (StackOverflowError e) {
            throw new ExpressionRejectedException(
                    what + " expression is too complex to evaluate: it exhausted the parser stack", e);
        }
    }

    /**
     * A rejection, and the wrapper for anything a parse or an evaluation throws. It extends
     * {@link RuntimeException} on purpose: every call site fails closed on {@code catch (Exception)},
     * so turning a {@link StackOverflowError} into one of these is exactly what puts an oversized
     * expression back on the ordinary "this guard did not evaluate, so the step does not run" path.
     */
    public static class ExpressionRejectedException extends RuntimeException {
        public ExpressionRejectedException(String message) {
            super(message);
        }

        public ExpressionRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
