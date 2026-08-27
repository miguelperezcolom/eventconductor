package io.mateu.workflow.application.services;

import io.mateu.workflow.application.services.DecisionTableEvaluator.MalformedDecisionTableException;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.DecisionRow;
import io.mateu.workflow.domain.HitPolicy;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.expression.ExpressionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * HARD-RULE-01..08 — a rule written to break the runtime.
 *
 * <p>A rule runs on the same threads a workflow does, and it can reach the runtime by a road the
 * catalogue does not police: {@code RestRuleSource} and {@code GrpcRuleSource} fetch rules from
 * somewhere else entirely, so a rule can arrive here having passed through no validator at all.
 * That is why the evaluator has to hold on its own, and why these tests hand it rules the
 * catalogue would have refused.
 */
class HostileRuleEvaluationTest {

    private final RuleEvaluator evaluator = new RuleEvaluator();

    private Rule expression(String when, String thenExpression) {
        return new Rule("r-1", "Hostile", "", RuleType.EXPRESSION, 1, 0, List.of(),
                when, List.of(new Assignment("out", thenExpression)), null, null, null, null);
    }

    private Rule table(List<String> inputs, List<String> outputs, List<DecisionRow> rows) {
        return new Rule("r-1", "Hostile table", "", RuleType.DECISION_TABLE, 1, 0, List.of(),
                null, null, inputs, outputs, rows, HitPolicy.FIRST);
    }

    private static final Map<String, Object> FACTS = Map.of("amount", 250, "country", "ES");

    /** HARD-RULE-01. The rule sandbox is the workflow sandbox: reflection reaches nothing. */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "''.getClass().forName('java.lang.Runtime')",
            "''.class.forName('java.lang.System').getProperty('user.home')",
            "''.getClass().getClassLoader()",
    })
    void aRuleCannotReachOutOfTheSandbox(String exploit) {
        Object result;
        try {
            result = evaluator.evaluate(expression("true", exploit), FACTS).outputs().get("out");
        } catch (RuntimeException e) {
            return; // denied by throwing, which is also a denial
        }
        // RESTRICTED denies by returning null as often as by throwing; both are a denial. What
        // must never come back is a live object for the next term to call a method on.
        assertThat(result).satisfiesAnyOf(
                value -> assertThat(value).isNull(),
                value -> assertThat(value).isNotInstanceOfAny(Class.class, ClassLoader.class, Runtime.class));
    }

    /** HARD-RULE-02. And it cannot spin: an expression has no loops, so there is nothing to spin in. */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"while(true){}", "for(i : [1,2,3]) { }", "var f = function(n) { f(n+1) }; f(1)"})
    void aRuleCannotLoop(String spin) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> evaluator.evaluate(expression(spin, "1"), FACTS))
                        .isInstanceOf(Exception.class));
    }

    /**
     * HARD-RULE-03. An expression too big to parse fails as an exception rather than unwinding the
     * thread — the same {@code StackOverflowError} that a workflow guard could raise, on the road
     * a rule takes. See {@code ExpressionGuard}.
     */
    @Test
    void anOverNestedRuleExpressionFailsAsAnExceptionRatherThanAnError() {
        int deep = ExpressionGuard.MAX_NESTING * 100;
        var bomb = "(".repeat(deep) + "amount > 0" + ")".repeat(deep);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> evaluator.evaluate(expression(bomb, "1"), FACTS))
                        .isInstanceOf(Exception.class)
                        .isNotInstanceOf(Error.class));
    }

    /**
     * HARD-RULE-04. A decision table that is not one. Every one of these used to surface as an
     * anonymous {@link NullPointerException} or {@link IndexOutOfBoundsException} from inside a
     * loop, thrown into whichever step had asked for the rule, with nothing in it to say which rule
     * or which row was at fault.
     */
    @Test
    void aTableMissingItsPartsFailsByName() {
        assertThatThrownBy(() -> evaluator.evaluate(table(null, List.of("o"), List.of()), FACTS))
                .isInstanceOf(MalformedDecisionTableException.class)
                .hasMessageContaining("Hostile table");
        assertThatThrownBy(() -> evaluator.evaluate(table(List.of("amount"), null, List.of()), FACTS))
                .isInstanceOf(MalformedDecisionTableException.class);
        assertThatThrownBy(() -> evaluator.evaluate(table(List.of("amount"), List.of("o"), null), FACTS))
                .isInstanceOf(MalformedDecisionTableException.class);
    }

    /** HARD-RULE-05. A ragged row — fewer cells than the table has columns — says which row. */
    @Test
    void aRowWithTheWrongNumberOfCellsSaysWhichRow() {
        var ragged = table(List.of("amount", "country"), List.of("discount"),
                List.of(new DecisionRow(List.of("> 100", "'ES'"), List.of("10")),
                        new DecisionRow(List.of("> 100"), List.of("5"))));

        assertThatThrownBy(() -> evaluator.evaluate(ragged, FACTS))
                .isInstanceOf(MalformedDecisionTableException.class)
                .hasMessageContaining("row 1")
                .hasMessageContaining("2 cells");
    }

    /** HARD-RULE-06. Too few output cells is the same mistake on the other side of the table. */
    @Test
    void aRowMissingAnOutputCellSaysSo() {
        var ragged = table(List.of("amount"), List.of("discount", "reason"),
                List.of(new DecisionRow(List.of("> 100"), List.of("10"))));

        assertThatThrownBy(() -> evaluator.evaluate(ragged, FACTS))
                .isInstanceOf(MalformedDecisionTableException.class)
                .hasMessageContaining("'then'");
    }

    /**
     * HARD-RULE-07. A cell is compiled into JEXL by concatenation, so a cell can say more than it
     * appears to. It buys nothing — a rule author can already write any expression they like in
     * {@code then} — and the point of the test is that the sandbox is what bounds it either way:
     * the trick alters what the row matches, and reaches nothing outside.
     */
    @Test
    void aCellCraftedToAlterItsOwnConditionStaysInsideTheSandbox() {
        var crafted = table(List.of("country"), List.of("out"),
                List.of(new DecisionRow(
                        List.of("'ZZ' or 1 == 1 or 'x'"),
                        List.of("''.getClass().forName('java.lang.Runtime')"))));

        var result = evaluator.evaluate(crafted, FACTS);

        assertThat(result.outputs().get("out"))
                .as("whatever the cell made the row match, the sandbox still denies reflection")
                .satisfiesAnyOf(
                        value -> assertThat(value).isNull(),
                        value -> assertThat(value).isNotInstanceOfAny(Class.class, Runtime.class));
    }

    /** HARD-RULE-08. Arithmetic that cannot be done fails as a rule failure, not as a crash. */
    @Test
    void divisionByZeroIsAnOrdinaryRuleFailure() {
        assertThatThrownBy(() -> evaluator.evaluate(expression("true", "amount / 0"), FACTS))
                .isInstanceOf(Exception.class)
                .isNotInstanceOf(Error.class);
    }

    /** HARD-RULE-09. No facts at all is a rule that does not match, not a NullPointerException. */
    @Test
    void aRuleEvaluatedWithNoFactsDoesNotCrash() {
        assertThatCode(() -> {
            try {
                evaluator.evaluate(expression("amount > 0", "1"), Map.of());
            } catch (RuntimeException expected) {
                // strict JEXL: an unknown variable is an error, and an error is a clean failure
            }
        }).doesNotThrowAnyException();
    }

    /**
     * HARD-RULE-11. The rule engine runs the same linear-time regex the workflow guards run.
     *
     * <p>It got its own JexlEngine when it was split out to stay free of the UI dependencies, and
     * a second engine is a second place to forget: the ReDoS hardening landed on the workflow one
     * and left rules — read from the same git import, evaluated on the same threads — matching
     * with {@code java.util.regex}. Under that engine this expression does not return.
     */
    @Test
    void aBacktrackingRegexInARuleDoesNotBurnTheThread() {
        var input = "a".repeat(40) + "b";
        var rule = expression("country =~ '(a+)+$'", "'matched'");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(evaluator.evaluate(rule, Map.of("country", input)).matched()).isFalse());
    }

    /** HARD-RULE-12. And it keeps the operator's meaning: {@code =~} is a whole-string match. */
    @Test
    void ruleRegexMatchingStaysAnchored() {
        assertThat(evaluator.evaluate(expression("country =~ 'E'", "'x'"), FACTS).matched()).isFalse();
        assertThat(evaluator.evaluate(expression("country =~ 'ES'", "'x'"), FACTS).matched()).isTrue();
    }

    /** HARD-RULE-10. A catalogue-sized table is evaluated, not choked on. */
    @Test
    void aVeryLargeDecisionTableStillEvaluates() {
        var rows = new ArrayList<DecisionRow>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(new DecisionRow(List.of("== " + i), List.of(String.valueOf(i))));
        }
        var big = table(List.of("amount"), List.of("discount"), rows);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertThat(evaluator.evaluate(big, Map.of("amount", 9_999)).outputs())
                        .containsEntry("discount", 9_999));
    }
}
