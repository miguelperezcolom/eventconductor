package io.mateu.workflow.domain;

import java.util.List;
import java.util.Map;

public record RuleEvaluationResult(
        boolean matched,
        Map<String, Object> outputs,
        // one entry per matching decision-table row (single entry for expression rules and FIRST tables)
        List<Map<String, Object>> collected
) {

    public static RuleEvaluationResult noMatch() {
        return new RuleEvaluationResult(false, Map.of(), List.of());
    }
}
