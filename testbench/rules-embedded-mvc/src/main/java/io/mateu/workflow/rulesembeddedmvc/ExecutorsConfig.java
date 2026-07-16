package io.mateu.workflow.rulesembeddedmvc;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.services.FactCoercer;
import io.mateu.workflow.application.usecases.evaluaterule.EvaluateRuleCommand;
import io.mateu.workflow.application.usecases.evaluaterule.EvaluateRuleUseCase;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedded-mode worker: routes RULE steps (taskId "evaluate-rule") to the rule
 * runtime and reports the rule outputs back as process variables.
 */
@Configuration
public class ExecutorsConfig {

    @Bean
    public EmbeddedTaskExecutor taskExecutor(EvaluateRuleUseCase evaluateRuleUseCase,
                                             FactCoercer factCoercer,
                                             UpdateStepExecutionUseCase updateStepExecution) {
        return request -> {
            if (!"evaluate-rule".equals(request.taskId())) {
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                        request.taskExecutionId(), List.of(), "", StepExecutionStatus.COMPLETED));
                return;
            }
            try {
                var ruleId = request.variables().stream()
                        .filter(v -> "ruleId".equals(v.name()))
                        .map(v -> v.value())
                        .findFirst().orElseThrow();
                var facts = factCoercer.toFacts(request.variables());
                var result = evaluateRuleUseCase.handle(new EvaluateRuleCommand(ruleId, facts));
                var outputs = new ArrayList<Variable>();
                result.outputs().forEach((name, value) ->
                        outputs.add(new Variable(name, value != null ? String.valueOf(value) : null)));
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                        request.taskExecutionId(), outputs,
                        "Rule " + ruleId + (result.matched() ? " matched: " + result.outputs() : " did not match"),
                        StepExecutionStatus.COMPLETED));
            } catch (Exception e) {
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                        request.taskExecutionId(), List.of(), e.getMessage(), StepExecutionStatus.ERROR));
            }
        };
    }
}
