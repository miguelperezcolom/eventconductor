package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleEvaluationResult;
import io.mateu.workflow.domain.RuleType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Entry point for embedders. Works standalone with {@link #evaluate(Rule, Map)}
 * or against a {@link RuleSource} to evaluate by id or tag.
 */
public class RuleEvaluator {

    private final ExpressionRuleEvaluator expressionRuleEvaluator;
    private final DecisionTableEvaluator decisionTableEvaluator;
    private final RuleSource ruleSource;

    public RuleEvaluator() {
        this(null);
    }

    public RuleEvaluator(RuleSource ruleSource) {
        var expressionEvaluator = new RuleExpressionEvaluator();
        this.expressionRuleEvaluator = new ExpressionRuleEvaluator(expressionEvaluator);
        this.decisionTableEvaluator = new DecisionTableEvaluator(expressionEvaluator, new CellConditionCompiler());
        this.ruleSource = ruleSource;
    }

    public RuleEvaluationResult evaluate(Rule rule, Map<String, Object> facts) {
        if (RuleType.DECISION_TABLE.equals(rule.type())) {
            return decisionTableEvaluator.evaluate(rule, facts);
        }
        return expressionRuleEvaluator.evaluate(rule, facts);
    }

    public RuleEvaluationResult evaluate(String ruleId, Map<String, Object> facts) {
        var rule = requireSource().findById(ruleId)
                .orElseThrow(() -> new NoSuchElementException("Rule not found: " + ruleId));
        return evaluate(rule, facts);
    }

    /**
     * Evaluates every rule carrying the tag, highest salience first. Each rule
     * sees the original facts plus the outputs accumulated so far, and the
     * merged outputs are returned.
     */
    public Map<String, Object> evaluateByTag(String tag, Map<String, Object> facts) {
        Map<String, Object> enrichedFacts = new LinkedHashMap<>(facts);
        Map<String, Object> outputs = new LinkedHashMap<>();
        requireSource().findAll().stream()
                .filter(rule -> rule.hasTag(tag))
                .sorted(Comparator.comparingInt(Rule::salience).reversed())
                .forEach(rule -> {
                    var result = evaluate(rule, enrichedFacts);
                    if (result.matched()) {
                        enrichedFacts.putAll(result.outputs());
                        outputs.putAll(result.outputs());
                    }
                });
        return outputs;
    }

    private RuleSource requireSource() {
        if (ruleSource == null) {
            throw new IllegalStateException("No RuleSource configured: use evaluate(Rule, facts) or provide a source");
        }
        return ruleSource;
    }
}
