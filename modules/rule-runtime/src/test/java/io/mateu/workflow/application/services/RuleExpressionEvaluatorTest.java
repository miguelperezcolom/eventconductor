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
}
