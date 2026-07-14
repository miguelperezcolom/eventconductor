package io.mateu.workflow.rulesembeddedheadless;

import io.mateu.workflow.application.services.RuleEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RulesHeadlessAppTests {

    @Autowired
    RuleEvaluator ruleEvaluator;

    private Map<String, Object> facts(String category, int total) {
        return Map.of("order", Map.of("total", total), "customer", Map.of("category", category));
    }

    @Test
    void expressionRuleFromClasspathEvaluates() {
        var result = ruleEvaluator.evaluate("high-value-order", facts("VIP", 200));

        assertThat(result.matched()).isTrue();
        assertThat(result.outputs()).containsEntry("discount", 20.0).containsEntry("approvalRequired", true);
    }

    @Test
    void expressionRuleDoesNotMatchBelowThreshold() {
        var result = ruleEvaluator.evaluate("high-value-order", facts("VIP", 50));

        assertThat(result.matched()).isFalse();
    }

    @Test
    void decisionTableFromClasspathEvaluates() {
        assertThat(ruleEvaluator.evaluate("shipping-costs", facts("VIP", 50)).outputs())
                .containsEntry("shippingCost", 0).containsEntry("courier", "express");
        assertThat(ruleEvaluator.evaluate("shipping-costs", facts("STANDARD", 150)).outputs())
                .containsEntry("shippingCost", 5);
        assertThat(ruleEvaluator.evaluate("shipping-costs", facts("STANDARD", 50)).outputs())
                .containsEntry("shippingCost", 10);
    }
}
