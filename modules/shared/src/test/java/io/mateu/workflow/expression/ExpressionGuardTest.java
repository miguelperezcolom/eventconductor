package io.mateu.workflow.expression;

import io.mateu.workflow.expression.ExpressionGuard.ExpressionRejectedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HARD-EXPR-01..05: the size and shape ceilings every expression clears before a parser sees it. */
class ExpressionGuardTest {

    @Test
    void anExpressionAnyoneWouldActuallyWritePasses() {
        assertThatNoException().isThrownBy(() ->
                ExpressionGuard.check("amount > 100 && country == 'ES' && !list.isEmpty()", "workflow"));
    }

    @Test
    void noExpressionIsNotAHazard() {
        assertThatNoException().isThrownBy(() -> ExpressionGuard.check(null, "workflow"));
        assertThatNoException().isThrownBy(() -> ExpressionGuard.check("", "workflow"));
    }

    @Test
    void anOversizedExpressionIsRejected() {
        var huge = "a > 0" + " || a > 0".repeat(ExpressionGuard.MAX_LENGTH);

        assertThatThrownBy(() -> ExpressionGuard.check(huge, "workflow"))
                .isInstanceOf(ExpressionRejectedException.class)
                .hasMessageContaining("character limit");
    }

    @Test
    void anExpressionExactlyAtTheLimitIsAccepted() {
        var atTheLimit = "a".repeat(ExpressionGuard.MAX_LENGTH);

        assertThatNoException().isThrownBy(() -> ExpressionGuard.check(atTheLimit, "workflow"));
    }

    @Test
    void anOverNestedExpressionIsRejectedWhateverTheBracket() {
        for (var brackets : new String[][]{{"(", ")"}, {"[", "]"}, {"{", "}"}}) {
            int deep = ExpressionGuard.MAX_NESTING + 1;
            var nested = brackets[0].repeat(deep) + "1" + brackets[1].repeat(deep);

            assertThatThrownBy(() -> ExpressionGuard.check(nested, "workflow"))
                    .as("nesting with %s%s must be capped too", brackets[0], brackets[1])
                    .isInstanceOf(ExpressionRejectedException.class)
                    .hasMessageContaining("deep");
        }
    }

    @Test
    void nestingIsDepthNotCount() {
        // MAX_NESTING sibling groups, never more than one deep: shape, not volume, is what costs
        // the parser a frame, so this has to pass or every long boolean chain would be refused.
        var wide = "(a > 0)".repeat(ExpressionGuard.MAX_NESTING * 4);

        assertThatNoException().isThrownBy(() -> ExpressionGuard.check(wide, "workflow"));
    }

    @Test
    void theRejectionNamesWhatWasRejected() {
        assertThatThrownBy(() -> ExpressionGuard.check("(".repeat(500), "correlation"))
                .hasMessageContaining("correlation");
    }

    @Test
    void aStackOverflowBecomesAnOrdinaryExceptionSoCallersFailClosed() {
        // The point of failClosed: every guard call site fails closed on `catch (Exception)`, and
        // a StackOverflowError is an Error — it would pass straight through and unwind the
        // orchestration thread instead of failing the one step whose definition was bad.
        assertThatThrownBy(() -> ExpressionGuard.failClosed("workflow", ExpressionGuardTest::overflowTheStack))
                .isInstanceOf(ExpressionRejectedException.class)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("too complex")
                .hasCauseInstanceOf(StackOverflowError.class);
    }

    @Test
    void failClosedLetsAValueAndAnOrdinaryFailureThrough() {
        assertThat(ExpressionGuard.failClosed("workflow", () -> "value")).isEqualTo("value");

        assertThatThrownBy(() -> ExpressionGuard.failClosed("workflow", () -> {
            throw new IllegalArgumentException("no such variable");
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("InfiniteRecursion")
    private static Object overflowTheStack() {
        return overflowTheStack();
    }
}
