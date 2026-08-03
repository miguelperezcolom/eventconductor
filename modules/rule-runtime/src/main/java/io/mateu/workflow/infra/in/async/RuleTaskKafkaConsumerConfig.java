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
import io.mateu.workflow.worker.WorkerReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the runtime into a rules worker: reacts to RULE workflow steps
 * (taskId "evaluate-rule"), evaluates the rule named by the ruleId variable
 * against the process variables and reports the outputs back upstream.
 *
 * <p>Like any other worker, it answers through {@link WorkerReply}: a refused reply is retried
 * and then thrown, so the listener fails, the offset is not committed and Kafka hands the task
 * back. Evaluation runs on the consumer thread for exactly that reason — handing it to a thread
 * of its own, as this once did, commits the offset immediately and there is nothing left to
 * redeliver when the reply cannot be published. A rule is an in-process expression or decision
 * table, so holding the consumer for its duration costs nothing worth having.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@ConditionalOnClass(StreamBridge.class)
public class RuleTaskKafkaConsumerConfig {

    public static final String EVALUATE_RULE_TASK_ID = "evaluate-rule";

    private static final Logger log = LoggerFactory.getLogger(RuleTaskKafkaConsumerConfig.class);

    private final RuleEvaluator ruleEvaluator;
    private final FactCoercer factCoercer;
    /** The {@code StreamBridge} bean, as the interface it implements — the final class cannot be
     *  stood in for, and a reply path nobody can test is not a reply path anyone should trust. */
    private final StreamOperations streamBridge;

    public RuleTaskKafkaConsumerConfig(RuleEvaluator ruleEvaluator,
                                       FactCoercer factCoercer,
                                       StreamOperations streamBridge) {
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
                evaluate(taskExecutionId, processId, variables);
            }
        };
    }

    /**
     * Evaluates and replies. A rule that fails to evaluate is a business outcome — it is reported
     * as an ERROR status, not retried — so the reply is published outside the catch below: a
     * failure to <em>publish</em> must escape, and swallowing it here is the bug this shape
     * exists to prevent.
     */
    private void evaluate(String taskExecutionId, String processId, List<Variable> variables) {
        var outputs = new ArrayList<Variable>();
        TaskStatus status;
        MessageType logType;
        String logMessage;
        try {
            var ruleId = variables.stream()
                    .filter(variable -> "ruleId".equals(variable.name()))
                    .findAny().orElseThrow(() -> new IllegalArgumentException("Missing ruleId variable"))
                    .value();
            var facts = factCoercer.toFacts(variables);
            var result = ruleEvaluator.evaluate(ruleId, facts);
            result.outputs().forEach((name, value) ->
                    outputs.add(new Variable(name, value != null ? String.valueOf(value) : null)));
            status = TaskStatus.COMPLETED;
            logType = MessageType.Info;
            logMessage = "Rule " + ruleId
                    + (result.matched() ? " matched: " + result.outputs() : " did not match");
        } catch (Exception e) {
            log.error("Rule evaluation failed for task execution {}", taskExecutionId, e);
            outputs.clear();
            status = TaskStatus.ERROR;
            logType = MessageType.Error;
            logMessage = e.getMessage();
        }

        emitLog(taskExecutionId, logType, logMessage);

        WorkerReply.send(streamBridge, new TaskStatusChanged(taskExecutionId, status, outputs, processId));
    }

    /**
     * The log line annotates the process timeline; losing it costs a line of history. It must
     * never be the reason the status reply does not go out.
     */
    private void emitLog(String taskExecutionId, MessageType type, String message) {
        try {
            streamBridge.send("upstream", new TaskLogEmitted(taskExecutionId, type, message));
        } catch (RuntimeException e) {
            log.warn("Could not publish the log line for task execution {}; continuing to the status reply",
                    taskExecutionId, e);
        }
    }
}
