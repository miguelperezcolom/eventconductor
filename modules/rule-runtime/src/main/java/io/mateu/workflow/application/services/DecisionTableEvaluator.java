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
        // A table is inputs, outputs and rows. The rule catalogue refuses one that is not — but a
        // rule can also arrive from a REST or gRPC source that never saw the validator, and without
        // this the first thing that happened was a NullPointerException from inside whatever step
        // was asking for the rule, with nothing in it to say which rule was at fault.
        requireTable(rule);
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

    /**
     * The shape a decision table has to have before any of it can be read. Stated as one check with
     * one message, because every way of getting it wrong used to surface as the same anonymous
     * {@link NullPointerException} or {@link IndexOutOfBoundsException} from deep inside a loop.
     */
    private void requireTable(Rule rule) {
        if (rule.rows() == null || rule.inputs() == null || rule.outputs() == null) {
            throw new MalformedDecisionTableException("Decision table '" + rule.name()
                    + "' must declare inputs, outputs and rows");
        }
        for (int i = 0; i < rule.rows().size(); i++) {
            var row = rule.rows().get(i);
            if (row.when() == null || row.when().size() != rule.inputs().size()) {
                throw new MalformedDecisionTableException("Decision table '" + rule.name() + "' row " + i
                        + ": 'when' must have " + rule.inputs().size() + " cells (one per input)");
            }
            if (row.then() == null || row.then().size() != rule.outputs().size()) {
                throw new MalformedDecisionTableException("Decision table '" + rule.name() + "' row " + i
                        + ": 'then' must have " + rule.outputs().size() + " cells (one per output)");
            }
        }
    }

    /** A table whose shape does not hold together, named so the failure says which rule and where. */
    public static class MalformedDecisionTableException extends RuntimeException {
        public MalformedDecisionTableException(String message) {
            super(message);
        }
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
