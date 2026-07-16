package io.mateu.workflow.application.usecases.evaluaterule;

import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.domain.RuleEvaluationResult;
import org.springframework.stereotype.Service;

@Service
public class EvaluateRuleUseCase {

    private final RuleEvaluator ruleEvaluator;

    public EvaluateRuleUseCase(RuleEvaluator ruleEvaluator) {
        this.ruleEvaluator = ruleEvaluator;
    }

    public RuleEvaluationResult handle(EvaluateRuleCommand command) {
        return ruleEvaluator.evaluate(command.ruleId(), command.facts());
    }
}
