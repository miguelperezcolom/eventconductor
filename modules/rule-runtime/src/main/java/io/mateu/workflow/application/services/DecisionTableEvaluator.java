package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.DecisionRow;
import io.mateu.workflow.domain.HitPolicy;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleEvaluationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DecisionTableEvaluator {

    private final RuleExpressionEvaluator expressionEvaluator;
    private final CellConditionCompiler cellConditionCompiler;

    public DecisionTableEvaluator(RuleExpressionEvaluator expressionEvaluator,
                                  CellConditionCompiler cellConditionCompiler) {
        this.expressionEvaluator = expressionEvaluator;
        this.cellConditionCompiler = cellConditionCompiler;
    }

    public RuleEvaluationResult evaluate(Rule rule, Map<String, Object> facts) {
        List<Map<String, Object>> collected = new ArrayList<>();
        for (DecisionRow row : rule.rows()) {
            if (!matches(rule, row, facts)) {
                continue;
            }
            var outputs = evaluateOutputs(rule, row, facts);
            if (rule.hitPolicy() == null || HitPolicy.FIRST.equals(rule.hitPolicy())) {
                return new RuleEvaluationResult(true, outputs, List.of(outputs));
            }
            collected.add(outputs);
        }
        if (collected.isEmpty()) {
            return RuleEvaluationResult.noMatch();
        }
        // COLLECT: merged view is last-write-wins per output name, in row order
        Map<String, Object> merged = new LinkedHashMap<>();
        collected.forEach(merged::putAll);
        return new RuleEvaluationResult(true, merged, collected);
    }

    private boolean matches(Rule rule, DecisionRow row, Map<String, Object> facts) {
        for (int i = 0; i < rule.inputs().size(); i++) {
            var condition = cellConditionCompiler.compile(rule.inputs().get(i), row.when().get(i));
            if (condition != null && !expressionEvaluator.evalPredicate(condition, facts)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> evaluateOutputs(Rule rule, DecisionRow row, Map<String, Object> facts) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (int i = 0; i < rule.outputs().size(); i++) {
            outputs.put(rule.outputs().get(i), expressionEvaluator.eval(row.then().get(i), facts));
        }
        return outputs;
    }
}
