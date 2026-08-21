package io.mateu.workflow.hardening;

import io.mateu.workflow.application.services.JEXLEvaluator;
import io.mateu.workflow.expression.ExpressionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * HARD-JEXL-01..07 — what a hostile expression cannot do.
 *
 * <p>Every expression the engine evaluates is untrusted input: a workflow definition arrives from a
 * git import or from the definition editor, and its guards, link conditions and correlation
 * expressions all run inside the orchestration thread that owns a process. This pins the four ways
 * such an expression could hurt the engine — reaching out of the sandbox, spinning forever,
 * rewriting the state it is reading, and exhausting the stack — and, just as importantly, pins that
 * an expression a person would actually write still evaluates.
 */
class JexlSandboxHardeningTest {

    private static final Map<String, Object> FACTS = Map.of(
            "amount", 250,
            "country", "ES",
            "name", "hello",
            "items", List.of(1, 2, 3));

    /**
     * HARD-JEXL-01. Reflection is the road to RCE, and RESTRICTED closes it. It closes it by
     * returning null rather than by throwing, so assert on the value: what must never happen is a
     * {@code Class} or a {@code Runtime} coming back for the next term to call a method on.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "''.getClass().forName('java.lang.Runtime')",
            "''.getClass().getClassLoader()",
            "''.class.forName('java.lang.System').getProperty('user.home')",
            "''.class.newInstance()",
            "''.class.getResource('/')",
    })
    void reflectionNeverYieldsSomethingToCallAMethodOn(String exploit) {
        Object result = evaluateOrNull(exploit);

        assertThat(result)
                .as("the sandbox must not hand a hostile guard a live object: %s", exploit)
                .satisfiesAnyOf(
                        r -> assertThat(r).isNull(),
                        r -> assertThat(r).isNotInstanceOfAny(Class.class, ClassLoader.class, Runtime.class));
    }

    /**
     * HARD-JEXL-02. A guard cannot loop, so it cannot spin: there is no expression that keeps an
     * orchestration thread busy, which is what makes evaluating one on that thread safe at all.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "while(true){}",
            "for(i : [1,2,3]) { }",
            "var s = 0; while(true) { s = s + 1 }; s",
            "var f = function(n) { f(n + 1) }; f(1)",
    })
    void aGuardCannotLoopOrRecurse(String spin) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> JEXLEvaluator.eval(spin, FACTS))
                        .as("a loop must be refused at parse time: %s", spin)
                        .isInstanceOf(Exception.class));
    }

    /** HARD-JEXL-03. A guard reads the process; it must not be able to rewrite it. */
    @Test
    void aGuardCannotAssignToTheProcessVariablesItReads() {
        assertThatThrownBy(() -> JEXLEvaluator.eval("amount = 0", FACTS))
                .isInstanceOf(Exception.class);
        assertThat(FACTS.get("amount")).isEqualTo(250);
    }

    /** HARD-JEXL-04. No instantiation, so no reaching a class the permissions would have allowed. */
    @Test
    void aGuardCannotInstantiateAnything() {
        assertThatThrownBy(() -> JEXLEvaluator.eval("new('java.io.File', '/etc/passwd')", FACTS))
                .isInstanceOf(Exception.class);
    }

    /**
     * HARD-JEXL-05. The one that used to unwind the thread. A few thousand brackets overflow JEXL's
     * recursive-descent parser, and a StackOverflowError is an Error: it went straight through every
     * fail-closed {@code catch (Exception)} around a guard evaluation and took out the orchestration
     * thread — the Kafka consumer, the timer scheduler — rather than failing the one bad step.
     */
    @Test
    void anOverNestedExpressionFailsAsAnExceptionRatherThanUnwindingTheThread() {
        int deep = ExpressionGuard.MAX_NESTING * 100;
        var bomb = "(".repeat(deep) + "amount > 0" + ")".repeat(deep);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> JEXLEvaluator.eval(bomb, FACTS))
                        .isInstanceOf(Exception.class)
                        .isNotInstanceOf(Error.class));
    }

    /** HARD-JEXL-06. Same for sheer length, which the parser would otherwise happily chew through. */
    @Test
    void anOversizedExpressionIsRefusedBeforeItIsParsed() {
        var huge = "amount > 0" + " || amount > 0".repeat(ExpressionGuard.MAX_LENGTH);

        assertThatThrownBy(() -> JEXLEvaluator.eval(huge, FACTS))
                .isInstanceOf(ExpressionGuard.ExpressionRejectedException.class)
                .hasMessageContaining("character limit");
    }

    /**
     * HARD-JEXL-07. The other half of the contract, and the reason the denial list is written the
     * way it is: hardening that broke the expressions definitions actually contain would be a
     * regression dressed as a fix.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "amount > 100",
            "amount > 100 && country == 'ES'",
            "country == 'ES' || country == 'PT'",
            "name.startsWith('he')",
            "name.length() > 3",
            "items.size() == 3",
            "amount > 100 ? 'big' : 'small'",
            "!(amount < 100)",
            "(amount * 2) - 100 > 0",
            "['ES','PT'].contains(country)",
    })
    void theExpressionsRealDefinitionsContainStillEvaluate(String guard) {
        assertThatCode(() -> JEXLEvaluator.eval(guard, FACTS))
                .as("hardening must not break a guard anyone would write: %s", guard)
                .doesNotThrowAnyException();
    }

    /** RESTRICTED denies by returning null as often as by throwing; both are a denial. */
    private Object evaluateOrNull(String expression) {
        try {
            return JEXLEvaluator.eval(expression, FACTS);
        } catch (Exception e) {
            return null;
        }
    }
}
