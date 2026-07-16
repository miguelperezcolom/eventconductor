package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.DecisionRow;
import io.mateu.workflow.domain.HitPolicy;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTableEvaluatorTest {

    private final DecisionTableEvaluator evaluator =
            new DecisionTableEvaluator(new RuleExpressionEvaluator(), new CellConditionCompiler());

    private Rule shippingCosts(HitPolicy hitPolicy) {
        return new Rule("shipping-costs", "Shipping costs", null, RuleType.DECISION_TABLE, 1, 0, null,
                null, null,
                List.of("customer.category", "order.total"),
                List.of("shippingCost", "courier"),
                List.of(
                        new DecisionRow(List.of("VIP", "*"), List.of("0", "'express'")),
                        new DecisionRow(List.of("*", "> 100"), List.of("5", "'standard'")),
                        new DecisionRow(List.of("*", "*"), List.of("10", "'standard'"))),
                hitPolicy);
    }

    private Map<String, Object> facts(String category, int total) {
        return Map.of("customer", Map.of("category", category), "order", Map.of("total", total));
    }

    @Test
    void firstHitPolicyReturnsFirstMatchingRow() {
        var result = evaluator.evaluate(shippingCosts(HitPolicy.FIRST), facts("VIP", 50));

        assertThat(result.matched()).isTrue();
        assertThat(result.outputs()).containsEntry("shippingCost", 0).containsEntry("courier", "express");
        assertThat(result.collected()).hasSize(1);
    }

    @Test
    void fallbackRowMatchesWhenNothingElseDoes() {
        var result = evaluator.evaluate(shippingCosts(null), facts("STANDARD", 50));

        assertThat(result.outputs()).containsEntry("shippingCost", 10);
    }

    @Test
    void collectHitPolicyGathersAllMatchingRows() {
        var result = evaluator.evaluate(shippingCosts(HitPolicy.COLLECT), facts("VIP", 200));

        assertThat(result.matched()).isTrue();
        assertThat(result.collected()).hasSize(3);
        // merged view is last-write-wins in row order
        assertThat(result.outputs()).containsEntry("shippingCost", 10).containsEntry("courier", "standard");
    }

    @Test
    void noMatchWhenNoRowApplies() {
        var rule = new Rule("r", "r", null, RuleType.DECISION_TABLE, 1, 0, null, null, null,
                List.of("order.total"), List.of("out"),
                List.of(new DecisionRow(List.of("> 1000"), List.of("1"))), HitPolicy.FIRST);

        var result = evaluator.evaluate(rule, Map.of("order", Map.of("total", 5)));

        assertThat(result.matched()).isFalse();
        assertThat(result.outputs()).isEmpty();
    }
}
