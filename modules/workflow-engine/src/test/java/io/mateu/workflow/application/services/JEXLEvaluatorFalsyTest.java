package io.mateu.workflow.application.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules a guard is evaluated by, pinned.
 *
 * <p>These exist because the behaviour they describe was arrived at from a production failure and
 * is easy to undo by accident. Two of them in particular:
 *
 * <ul>
 *   <li>{@link Undefined#negationOfAnUndefinedVariableIsTrue()} is the one that matters. Relaxing
 *       only the engine's {@code strict} flag makes {@code missing} resolve to null while
 *       {@code !missing} still throws — which is exactly the case that strands a process, so a
 *       half-change passes a casual read and fixes nothing.</li>
 *   <li>{@link Truthiness#theStringFalseIsFalsy()} is a deliberate departure from JavaScript, where
 *       any non-empty string is truthy. Every process variable here is a string, so
 *       {@code "false"} must be falsy or every negated guard in every deployed definition flips.</li>
 * </ul>
 */
class JEXLEvaluatorFalsyTest {

    @Nested
    @DisplayName("an undefined variable is falsy, not an error")
    class Undefined {

        @Test
        void anUndefinedVariableEvaluatesToNull() {
            assertThat(JEXLEvaluator.eval("missing", Map.of("other", "1"))).isNull();
        }

        /**
         * The whole point. Both sides of a two-way branch used to throw, so neither was eligible
         * and the process stopped with every downstream step still CREATED — and a step that never
         * started has no deadline, so no timeout could rescue it.
         */
        @Test
        void negationOfAnUndefinedVariableIsTrue() {
            assertThat(JEXLEvaluator.eval("!missing", Map.of("other", "1"))).isEqualTo(true);
        }

        @Test
        void comparingAnUndefinedVariableIsFalseRatherThanAnError() {
            assertThat(JEXLEvaluator.eval("decision == 'WALK'", Map.of())).isEqualTo(false);
        }

        @Test
        void anUndefinedVariableInAConjunctionDoesNotThrow() {
            assertThatCode(() -> JEXLEvaluator.eval(
                    "cancellable && ratePlan == 'NON_REFUNDABLE'", Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        void definedVariablesStillWinNormally() {
            assertThat(JEXLEvaluator.eval("paymentReceived == 'true'",
                    Map.of("paymentReceived", "true"))).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("undefined references are reported, so a typo is not silent")
    class Reporting {

        @Test
        void namesTheVariableThatWasNotSet() {
            var evaluation = JEXLEvaluator.evaluate("!upgradeAvailable", Map.of());
            assertThat(evaluation.value()).isEqualTo(true);
            assertThat(evaluation.undefinedVariables()).containsExactly("upgradeAvailable");
        }

        /**
         * Short-circuiting means only the reference that actually decided the result is reported,
         * which is the useful one rather than all of them.
         */
        @Test
        void reportsOnlyWhatWasActuallyRead() {
            var evaluation = JEXLEvaluator.evaluate(
                    "cancellable && ratePlan == 'NON_REFUNDABLE'", Map.of());
            assertThat(evaluation.undefinedVariables()).containsExactly("cancellable");
        }

        @Test
        void reportsNothingWhenEveryReferenceResolves() {
            var evaluation = JEXLEvaluator.evaluate("approved == 'true'",
                    Map.of("approved", "true"));
            assertThat(evaluation.undefinedVariables()).isEmpty();
        }
    }

    @Nested
    @DisplayName("what falsy semantics must NOT have relaxed")
    class StillFails {

        /**
         * The test this engine did not have. Non-strict arithmetic is what makes an undefined
         * variable falsy, and it also stops {@code x / 0} from throwing unless it is put back —
         * which it is. The rules engine caught this on its own (HARD-RULE-08); nothing here did.
         */
        @Test
        void divisionByZeroStillThrows() {
            assertThatThrownBy(() -> JEXLEvaluator.eval("amount / 0", Map.of("amount", 10)))
                    .isNotInstanceOf(Error.class)
                    .hasRootCauseInstanceOf(ArithmeticException.class);
        }

        @Test
        void moduloByZeroStillThrows() {
            assertThatThrownBy(() -> JEXLEvaluator.eval("amount % 0", Map.of("amount", 10)))
                    .isNotInstanceOf(Error.class)
                    .hasRootCauseInstanceOf(ArithmeticException.class);
        }

        @Test
        void ordinaryDivisionStillWorks() {
            assertThat(JEXLEvaluator.eval("amount / 2", Map.of("amount", 10)))
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("truthiness of a result")
    class Truthiness {

        /**
         * NOT JavaScript, and it must stay that way: process variables are strings, so a variable
         * holding "false" is what {@code !approved} reads.
         */
        @Test
        void theStringFalseIsFalsy() {
            assertThat(JEXLEvaluator.eval("!approved", Map.of("approved", "false")))
                    .isEqualTo(true);
        }

        @Test
        void aNonEmptyStringIsTruthy() {
            assertThat(JEXLEvaluator.eval("!name", Map.of("name", "Ana"))).isEqualTo(false);
        }
    }
}
