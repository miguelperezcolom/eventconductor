package io.mateu.workflow.application.services;

import org.apache.commons.jexl3.JexlException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleExpressionEvaluatorTest {

    private final RuleExpressionEvaluator evaluator = new RuleExpressionEvaluator();

    @Test
    void evaluatesArithmeticOverNestedFacts() {
        var facts = Map.<String, Object>of("order", Map.of("total", 200));

        assertThat(evaluator.eval("order.total * 0.1", facts)).isEqualTo(20.0);
    }

    @Test
    void predicateIsTrueForBooleanTrue() {
        var facts = Map.<String, Object>of("order", Map.of("total", 200));

        assertThat(evaluator.evalPredicate("order.total > 100", facts)).isTrue();
        assertThat(evaluator.evalPredicate("order.total > 500", facts)).isFalse();
    }

    @Test
    void predicateFollowsWorkflowTruthinessForStrings() {
        assertThat(evaluator.evalPredicate("'yes'", Map.of())).isTrue();
        assertThat(evaluator.evalPredicate("'false'", Map.of())).isFalse();
        assertThat(evaluator.evalPredicate("''", Map.of())).isFalse();
    }

    @Test
    void parseRejectsInvalidExpressions() {
        assertThatThrownBy(() -> evaluator.parse("order.total >")).isInstanceOf(JexlException.class);
    }

    @Test
    void parseAcceptsValidExpressions() {
        evaluator.parse("customer.category == 'VIP' && order.total > 100");
    }

    // Rule expressions are untrusted (imported from git / edited in the UI). The engine must run
    // sandboxed (JexlPermissions.RESTRICTED) so an expression cannot reach System, Runtime or
    // reflection — otherwise a rule author has arbitrary code execution on the rules service.
    // RESTRICTED leaves the forbidden method unresolved, so the dangerous call never runs and the
    // sub-expression collapses to null instead of yielding a live Class/Runtime handle.

    @Test
    void blocksReflectivePivotFromFact() {
        var facts = Map.<String, Object>of("order", Map.of("total", 200));

        // getClass() may resolve, but the pivot to Class.forName — the actual RCE vector — is blocked.
        assertThat(evaluator.eval("order.getClass().forName('java.lang.Runtime')", facts)).isNull();
    }

    @Test
    void blocksSystemExit() {
        var facts = Map.<String, Object>of("code", 1);

        assertThat(evaluator.eval("''.getClass().forName('java.lang.System')", facts)).isNull();
    }

    @Test
    void blocksRuntimeExec() {
        assertThat(evaluator.eval("''.getClass().forName('java.lang.Runtime')", Map.of())).isNull();
    }
}
