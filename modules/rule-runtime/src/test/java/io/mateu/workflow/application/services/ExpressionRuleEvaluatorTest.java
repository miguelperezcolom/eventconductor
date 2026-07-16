package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionRuleEvaluatorTest {

    private final ExpressionRuleEvaluator evaluator = new ExpressionRuleEvaluator(new RuleExpressionEvaluator());

    private final Rule rule = new Rule("high-value-order", "High value order", null, RuleType.EXPRESSION, 1, 10,
            List.of("orders"),
            "order.total > 100 && customer.category == 'VIP'",
            List.of(new Assignment("discount", "order.total * 0.1"),
                    new Assignment("approvalRequired", "true")),
            null, null, null, null);

    @Test
    void producesOutputsWhenConditionHolds() {
        var facts = Map.<String, Object>of(
                "order", Map.of("total", 200),
                "customer", Map.of("category", "VIP"));

        var result = evaluator.evaluate(rule, facts);

        assertThat(result.matched()).isTrue();
        assertThat(result.outputs()).containsEntry("discount", 20.0).containsEntry("approvalRequired", true);
    }

    @Test
    void doesNotMatchWhenConditionFails() {
        var facts = Map.<String, Object>of(
                "order", Map.of("total", 50),
                "customer", Map.of("category", "VIP"));

        var result = evaluator.evaluate(rule, facts);

        assertThat(result.matched()).isFalse();
        assertThat(result.outputs()).isEmpty();
    }

    @Test
    void ruleWithoutConditionAlwaysMatches() {
        var unconditional = new Rule("r", "r", null, RuleType.EXPRESSION, 1, 0, null,
                null, List.of(new Assignment("fixed", "42")), null, null, null, null);

        var result = evaluator.evaluate(unconditional, Map.of());

        assertThat(result.matched()).isTrue();
        assertThat(result.outputs()).containsEntry("fixed", 42);
    }
}
