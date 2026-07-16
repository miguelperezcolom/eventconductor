package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.DecisionRow;
import io.mateu.workflow.domain.HitPolicy;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleValidatorTest {

    static RuleValidator validator;

    @BeforeAll
    static void init() throws IOException {
        validator = new RuleValidator();
        validator.init();
    }

    @Test
    void validExpressionRulePasses() {
        var rule = new Rule("r1", "Rule one", null, RuleType.EXPRESSION, 1, 0, List.of("orders"),
                "order.total > 100", List.of(new Assignment("discount", "order.total * 0.1")),
                null, null, null, null);

        assertThatCode(() -> validator.validate(rule)).doesNotThrowAnyException();
    }

    @Test
    void validDecisionTablePasses() {
        var rule = new Rule("r2", "Table", null, RuleType.DECISION_TABLE, 1, 0, null, null, null,
                List.of("customer.category"), List.of("cost"),
                List.of(new DecisionRow(List.of("VIP"), List.of("0"))), HitPolicy.FIRST);

        assertThatCode(() -> validator.validate(rule)).doesNotThrowAnyException();
    }

    @Test
    void expressionRuleWithoutThenFails() {
        var rule = new Rule("r3", "No then", null, RuleType.EXPRESSION, 1, 0, null,
                "true", null, null, null, null, null);

        assertThatThrownBy(() -> validator.validate(rule))
                .isInstanceOf(RuleValidator.RuleValidationException.class);
    }

    @Test
    void missingNameFails() {
        var rule = new Rule("r4", null, null, RuleType.EXPRESSION, 1, 0, null,
                null, List.of(new Assignment("out", "1")), null, null, null, null);

        assertThatThrownBy(() -> validator.validate(rule))
                .isInstanceOf(RuleValidator.RuleValidationException.class);
    }

    @Test
    void invalidJexlExpressionFails() {
        var rule = new Rule("r5", "Bad JEXL", null, RuleType.EXPRESSION, 1, 0, null,
                "order.total >", List.of(new Assignment("out", "1")), null, null, null, null);

        assertThatThrownBy(() -> validator.validate(rule))
                .isInstanceOf(RuleValidator.RuleValidationException.class)
                .hasMessageContaining("invalid JEXL");
    }

    @Test
    void rowArityMismatchFails() {
        var rule = new Rule("r6", "Bad table", null, RuleType.DECISION_TABLE, 1, 0, null, null, null,
                List.of("a", "b"), List.of("out"),
                List.of(new DecisionRow(List.of("*"), List.of("1"))), HitPolicy.FIRST);

        assertThatThrownBy(() -> validator.validate(rule))
                .isInstanceOf(RuleValidator.RuleValidationException.class)
                .hasMessageContaining("must have 2 cells");
    }

    @Test
    void invalidCellExpressionFails() {
        var rule = new Rule("r7", "Bad cell", null, RuleType.DECISION_TABLE, 1, 0, null, null, null,
                List.of("a"), List.of("out"),
                List.of(new DecisionRow(List.of("> "), List.of("1"))), HitPolicy.FIRST);

        assertThatThrownBy(() -> validator.validate(rule))
                .isInstanceOf(RuleValidator.RuleValidationException.class);
    }
}
