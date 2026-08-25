package io.mateu.workflow.input;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.input.InputLimits.InputRejectedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-LIM-01..09 — the limits themselves: what passes, what does not, and — the ones that matter most — the cases
 * where "nothing was sent" must not be mistaken for "something too big was sent".
 */
class InputLimitsTest {

    @Test
    void anIdentifierAtTheLimitPassesAndOneCharacterMoreDoesNot() {
        assertThatCode(() -> InputLimits.checkIdentifier(
                "k".repeat(InputLimits.MAX_IDENTIFIER_LENGTH), "businessKey"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> InputLimits.checkIdentifier(
                "k".repeat(InputLimits.MAX_IDENTIFIER_LENGTH + 1), "businessKey"))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("businessKey")
                .hasMessageContaining(String.valueOf(InputLimits.MAX_IDENTIFIER_LENGTH));
    }

    /** The rejection has to be readable, which it is not if it quotes the offending value back whole. */
    @Test
    void aRejectionQuotesAnExcerptRatherThanTheWholeValue() {
        var huge = "k".repeat(100_000);

        assertThatThrownBy(() -> InputLimits.checkIdentifier(huge, "correlationKey"))
                .isInstanceOf(InputRejectedException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
    }

    @Test
    void nothingSentIsNotSomethingTooBig() {
        assertThatCode(() -> {
            InputLimits.checkIdentifier(null, "businessKey");
            InputLimits.checkIdentifier("", "businessKey");
            InputLimits.checkText(null, "a log message");
            InputLimits.checkVariables(null, "an event");
            InputLimits.checkVariables(List.of(), "an event");
        }).doesNotThrowAnyException();
    }

    /** A megabyte in one variable is carried, and always has been: the ceiling is above it, not at it. */
    @Test
    void aMegabyteInOneVariableIsStillAccepted() {
        var oneMegabyte = "x".repeat(1_048_576);

        assertThatCode(() -> InputLimits.checkVariables(
                List.of(new Variable("payload", oneMegabyte)), "a process creation"))
                .doesNotThrowAnyException();
    }

    @Test
    void aValueOverTheLimitIsRefusedAndTheMessageNamesTheVariable() {
        var tooBig = "x".repeat(InputLimits.MAX_VALUE_LENGTH + 1);

        assertThatThrownBy(() -> InputLimits.checkVariables(
                List.of(new Variable("payload", tooBig)), "a process creation"))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("payload")
                .hasMessageContaining("a process creation");
    }

    @Test
    void aVariableNameTooLongToBeANameIsRefused() {
        var name = "n".repeat(InputLimits.MAX_IDENTIFIER_LENGTH + 1);

        assertThatThrownBy(() -> InputLimits.checkVariables(
                List.of(new Variable(name, "v")), "a worker reply"))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("name");
    }

    @Test
    void tooManyVariablesAreRefusedByCountBeforeAnythingIsMeasured() {
        var many = IntStream.rangeClosed(0, InputLimits.MAX_VARIABLES)
                .mapToObj(i -> new Variable("v" + i, "x"))
                .toList();

        assertThatThrownBy(() -> InputLimits.checkVariables(many, "a process creation"))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining(String.valueOf(InputLimits.MAX_VARIABLES));
    }

    /**
     * The limit that makes the others mean anything. Every variable here is well under the per-value
     * ceiling and there are far fewer than the count allows; together they are more than the engine
     * will take in one event.
     */
    @Test
    void variablesThatAreEachAcceptableAreRefusedWhenTheyAddUpToTooMuch() {
        var half = "x".repeat(InputLimits.MAX_VALUE_LENGTH / 2);
        var count = (int) (InputLimits.MAX_TOTAL_LENGTH / half.length()) + 2;
        var variables = IntStream.range(0, count)
                .mapToObj(i -> new Variable("v" + i, half))
                .toList();

        assertThat(count).isLessThan(InputLimits.MAX_VARIABLES);
        assertThatThrownBy(() -> InputLimits.checkVariables(variables, "a process creation"))
                .isInstanceOf(InputRejectedException.class)
                .hasMessageContaining("across its variables");
    }

    @Test
    void aNullVariableOrValueIsSkippedRatherThanCrashingTheCheck() {
        var variables = new java.util.ArrayList<Variable>();
        variables.add(null);
        variables.add(new Variable("v", null));
        variables.add(new Variable(null, "x"));

        assertThatCode(() -> InputLimits.checkVariables(variables, "a worker reply"))
                .doesNotThrowAnyException();
    }
}
