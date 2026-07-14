package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.services.FactCoercer;
import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the runtime into a rules worker: reacts to RULE workflow steps
 * (taskId "evaluate-rule"), evaluates the rule named by the ruleId variable
 * against the process variables and reports the outputs back upstream.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@ConditionalOnClass(StreamBridge.class)
public class RuleTaskKafkaConsumerConfig {

    public static final String EVALUATE_RULE_TASK_ID = "evaluate-rule";

    private static final Logger log = LoggerFactory.getLogger(RuleTaskKafkaConsumerConfig.class);

    private final RuleEvaluator ruleEvaluator;
    private final FactCoercer factCoercer;
    private final StreamBridge streamBridge;

    public RuleTaskKafkaConsumerConfig(RuleEvaluator ruleEvaluator,
                                       FactCoercer factCoercer,
                                       StreamBridge streamBridge) {
        this.ruleEvaluator = ruleEvaluator;
        this.factCoercer = factCoercer;
        this.streamBridge = streamBridge;
    }

    @Bean
    public java.util.function.Consumer<DomainEvent> consumeWorkerEventForRuleRuntime() {
        return event -> {
            if (event instanceof TaskExecutionRequested(
                    String taskExecutionId, String processId, String workflowDefinitionId, String stepId,
                    String taskId, List<Variable> variables)
                    && EVALUATE_RULE_TASK_ID.equals(taskId)) {
                new Thread(() -> evaluate(taskExecutionId, variables)).start();
            }
        };
    }

    private void evaluate(String taskExecutionId, List<Variable> variables) {
        try {
            var ruleId = variables.stream()
                    .filter(variable -> "ruleId".equals(variable.name()))
                    .findAny().orElseThrow(() -> new IllegalArgumentException("Missing ruleId variable"))
                    .value();
            var facts = factCoercer.toFacts(variables);
            var result = ruleEvaluator.evaluate(ruleId, facts);
            var outputs = new ArrayList<Variable>();
            result.outputs().forEach((name, value) ->
                    outputs.add(new Variable(name, value != null ? String.valueOf(value) : null)));
            streamBridge.send("upstream", new TaskLogEmitted(taskExecutionId, MessageType.Info,
                    "Rule " + ruleId + (result.matched() ? " matched: " + result.outputs() : " did not match")));
            streamBridge.send("upstream", new TaskStatusChanged(taskExecutionId, TaskStatus.COMPLETED, outputs));
        } catch (Exception e) {
            log.error("Rule evaluation failed for task execution {}", taskExecutionId, e);
            streamBridge.send("upstream", new TaskLogEmitted(taskExecutionId, MessageType.Error, e.getMessage()));
            streamBridge.send("upstream", new TaskStatusChanged(taskExecutionId, TaskStatus.ERROR, List.of()));
        }
    }
}
