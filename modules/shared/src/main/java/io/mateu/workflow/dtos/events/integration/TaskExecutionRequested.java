package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * A task the engine hands to a worker.
 *
 * @param attempt which dispatch of the step execution this is. The step execution id stays the
 *                same when a step runs again — a process restarted from the top, a step retried
 *                after a timeout — so the id alone cannot tell the reply of the attempt that is
 *                running from a late reply of one that was abandoned. A worker echoes it back on
 *                its {@link TaskStatusChanged} ({@code WorkerReply} does) and the engine drops a
 *                reply whose attempt is not the current one. Appended to the record and optional:
 *                null on a task an older engine sent, and a worker that does not echo it is
 *                answered as before, without the check.
 * @param priority the step's {@code priority} (0–100, 2.33) when it declares one; null means the default, 50,
 *                and is left off the wire. Honoured by the HTTP worker queue and the embedded pool; a Kafka
 *                topic keeps its own order, so a Kafka worker may read it but nothing reorders the topic.
 */
public record TaskExecutionRequested(
        String taskExecutionId,
        String processId,
        String workflowDefinitionId,
        String stepId,
        String taskId,
        List<Variable> variables,
        Integer attempt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Integer priority) implements DomainEvent {

    /** The shape before the attempt was carried: a task with no attempt is answered unchecked. */
    public TaskExecutionRequested(String taskExecutionId, String processId, String workflowDefinitionId,
                                  String stepId, String taskId, List<Variable> variables) {
        this(taskExecutionId, processId, workflowDefinitionId, stepId, taskId, variables, null, null);
    }

    /** The shape before task priority (2.33): the default priority. */
    public TaskExecutionRequested(String taskExecutionId, String processId, String workflowDefinitionId,
                                  String stepId, String taskId, List<Variable> variables, Integer attempt) {
        this(taskExecutionId, processId, workflowDefinitionId, stepId, taskId, variables, attempt, null);
    }

    /** The priority it is served with: the step's declared one (0–100), or 50. */
    public int effectivePriority() {
        return priority == null ? 50 : Math.max(0, Math.min(100, priority));
    }

    @Override
    public String partitionKey() {
        return processId;
    }
}
