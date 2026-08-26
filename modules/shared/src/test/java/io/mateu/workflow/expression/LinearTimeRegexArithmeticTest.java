package io.mateu.workflow.expression;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The arithmetic both evaluators share. What it must do is stop a regex burning a thread; what it
 * must not do is change what {@code =~} means, or refuse a pattern anyone actually writes.
 */
class LinearTimeRegexArithmeticTest {

    private final JexlEngine jexl = new JexlBuilder()
            .arithmetic(new LinearTimeRegexArithmetic(true))
            .strict(true)
            .create();

    private Object eval(String expression) {
        return jexl.createExpression(expression).evaluate(new MapContext(Map.of(
                "country", "ES",
                "items", List.of("a", "b"))));
    }

    /**
     * The pattern that motivates the whole class. Under {@code java.util.regex} this is
     * exponential in the length of the input and does not return in any useful time; under RE2 it
     * is linear and returns at once.
     */
    @Test
    void catastrophicBacktrackingReturnsInsteadOfBurningTheThread() {
        var input = "a".repeat(40) + "b";

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(eval("'" + input + "' =~ '(a+)+$'")).isEqualTo(false));
    }

    /**
     * JEXL's {@code =~} is an anchored, whole-string match — its own {@code contains} calls
     * {@code Matcher.matches()}. RE2J offers {@code find()} too, and picking it would turn every
     * guard into a substring test and invert the ones written with {@code !~}.
     */
    @Test
    void matchingStaysAnchoredToTheWholeString() {
        assertThat(eval("'hello world' =~ 'world'")).isEqualTo(false);
        assertThat(eval("'hello world' =~ '.*world.*'")).isEqualTo(true);
        assertThat(eval("country =~ 'E'")).isEqualTo(false);
        assertThat(eval("country =~ 'ES'")).isEqualTo(true);

        // The negated form is the one that fails open if this regresses: a step guarded on "the
        // country is not ES" must not start running for ESP.
        assertThat(eval("'ESP' !~ 'ES'")).isEqualTo(true);
        assertThat(eval("country !~ 'ES'")).isEqualTo(false);
    }

    /** {@code =~} is also membership, and that half belongs to JEXL, not to the regex engine. */
    @Test
    void membershipInACollectionStillWorks() {
        assertThat(eval("'a' =~ items")).isEqualTo(true);
        assertThat(eval("'z' =~ items")).isEqualTo(false);
    }

    /**
     * The price of linear time. These are valid Java patterns, so the refusal has to say what is
     * actually wrong — someone reading "invalid pattern" would go hunting for a typo that is not
     * there.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "^(?=.*x).*$",
            "^(?!foo).*$",
            "(?<=a)b",
    })
    void aLookaroundIsRefusedWithAReasonRatherThanAShrug(String pattern) {
        assertThatThrownBy(() -> eval("'anything' =~ '" + pattern + "'"))
                .hasRootCauseInstanceOf(com.google.re2j.PatternSyntaxException.class)
                .rootCause()
                .isNotNull();

        assertThatThrownBy(() -> new LinearTimeRegexArithmetic(true).contains(pattern, "anything"))
                .isInstanceOf(LinearTimeRegexArithmetic.UnsupportedRegexException.class)
                .hasMessageContaining("lookahead or lookbehind")
                .hasMessageContaining(pattern);
    }

    @Test
    void aBackreferenceIsRefusedWithItsOwnReason() {
        assertThatThrownBy(() -> new LinearTimeRegexArithmetic(true).contains("(a)\\1", "aa"))
                .isInstanceOf(LinearTimeRegexArithmetic.UnsupportedRegexException.class)
                .hasMessageContaining("backreference");
    }

    /**
     * The other half of the contract: hardening that refused the patterns definitions actually
     * contain would be a regression dressed as a fix.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "[A-Z]{2}",
            "\\d{4}-\\d{2}-\\d{2}",
            "(ES|PT|FR)",
            ".*",
            "[a-z0-9_-]+",
            "ORDER-\\d+",
    })
    void thePatternsRealDefinitionsContainStillEvaluate(String pattern) {
        assertThat(eval("'ES' =~ '" + pattern + "'")).isIn(true, false);
    }
}
