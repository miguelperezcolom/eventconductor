package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEvaluatorTest {

    private Rule expressionRule(String id, int salience, String when, String outputName, String expression) {
        return new Rule(id, id, null, RuleType.EXPRESSION, 1, salience, List.of("pricing"),
                when, List.of(new Assignment(outputName, expression)), null, null, null, null);
    }

    private final RuleSource source = new RuleSource() {
        final List<Rule> rules = List.of(
                expressionRule("base-price", 10, null, "price", "units * 5"),
                expressionRule("bulk-discount", 5, "units > 10", "price", "price * 0.9"));

        @Override
        public Optional<Rule> findById(String id) {
            return rules.stream().filter(rule -> rule.id().equals(id)).findFirst();
        }

        @Override
        public List<Rule> findAll() {
            return rules;
        }
    };

    @Test
    void evaluatesByIdThroughTheSource() {
        var result = new RuleEvaluator(source).evaluate("base-price", Map.of("units", 3));

        assertThat(result.outputs()).containsEntry("price", 15);
    }

    @Test
    void evaluateByTagChainsBySalienceEnrichingFacts() {
        var outputs = new RuleEvaluator(source).evaluateByTag("pricing", Map.of("units", 20));

        // base-price (salience 10) runs first, bulk-discount sees its output
        assertThat(outputs).containsEntry("price", 90.0);
    }

    @Test
    void unknownRuleIdFails() {
        assertThatThrownBy(() -> new RuleEvaluator(source).evaluate("nope", Map.of()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void withoutSourceOnlyDirectEvaluationWorks() {
        var evaluator = new RuleEvaluator();

        assertThat(evaluator.evaluate(expressionRule("r", 0, null, "out", "1"), Map.of()).outputs())
                .containsEntry("out", 1);
        assertThatThrownBy(() -> evaluator.evaluate("r", Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
