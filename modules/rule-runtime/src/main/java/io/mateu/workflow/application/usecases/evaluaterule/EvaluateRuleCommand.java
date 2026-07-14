package io.mateu.workflow.application.usecases.evaluaterule;

import java.util.Map;

public record EvaluateRuleCommand(String ruleId, Map<String, Object> facts) {
}
