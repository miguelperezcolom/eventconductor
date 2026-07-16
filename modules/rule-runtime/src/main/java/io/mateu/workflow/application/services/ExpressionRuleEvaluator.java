package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleEvaluationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExpressionRuleEvaluator {

    private final RuleExpressionEvaluator expressionEvaluator;

    public ExpressionRuleEvaluator(RuleExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    public RuleEvaluationResult evaluate(Rule rule, Map<String, Object> facts) {
        if (rule.when() != null && !rule.when().isBlank()
                && !expressionEvaluator.evalPredicate(rule.when(), facts)) {
            return RuleEvaluationResult.noMatch();
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (rule.then() != null) {
            rule.then().forEach(assignment ->
                    outputs.put(assignment.name(), expressionEvaluator.eval(assignment.expression(), facts)));
        }
        return new RuleEvaluationResult(true, outputs, List.of(outputs));
    }
}
