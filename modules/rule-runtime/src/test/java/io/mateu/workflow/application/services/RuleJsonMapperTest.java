package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.HitPolicy;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleJsonMapperTest {

    private final RuleJsonMapper mapper = new RuleJsonMapper();

    @Test
    void parsesExpressionRuleFromYaml() {
        var rule = mapper.toRule("""
                id: high-value-order
                name: High value order approval
                type: expression
                salience: 10
                tags: [orders]
                when: "order.total > 100"
                then:
                  - name: discount
                    expression: "order.total * 0.1"
                """);

        assertThat(rule.id()).isEqualTo("high-value-order");
        assertThat(rule.type()).isEqualTo(RuleType.EXPRESSION);
        assertThat(rule.salience()).isEqualTo(10);
        assertThat(rule.then()).hasSize(1);
    }

    @Test
    void parsesDecisionTableFromJson() {
        var rule = mapper.toRule("""
                { "id": "shipping-costs", "name": "Shipping costs", "type": "decision-table",
                  "hitPolicy": "COLLECT",
                  "inputs": ["customer.category"], "outputs": ["cost"],
                  "rows": [ { "when": ["VIP"], "then": ["0"] } ] }
                """);

        assertThat(rule.type()).isEqualTo(RuleType.DECISION_TABLE);
        assertThat(rule.hitPolicy()).isEqualTo(HitPolicy.COLLECT);
        assertThat(rule.rows()).hasSize(1);
    }

    @Test
    void roundTripsThroughCanonicalJson() {
        var rule = mapper.toRule("""
                id: r1
                name: Rule one
                type: expression
                when: "true"
                then: [ { name: out, expression: "1" } ]
                """);

        var reparsed = mapper.toRule(mapper.toJson(rule));

        assertThat(reparsed).isEqualTo(rule);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> mapper.toRule("{ garbage")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownRuleType() {
        assertThatThrownBy(() -> mapper.toRule("{\"name\": \"x\", \"type\": \"dmn\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
