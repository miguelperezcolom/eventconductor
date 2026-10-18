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
 */
public record TaskExecutionRequested(
        String taskExecutionId,
        String processId,
        String workflowDefinitionId,
        String stepId,
        String taskId,
        List<Variable> variables,
        Integer attempt) implements DomainEvent {

    /** The shape before the attempt was carried: a task with no attempt is answered unchecked. */
    public TaskExecutionRequested(String taskExecutionId, String processId, String workflowDefinitionId,
                                  String stepId, String taskId, List<Variable> variables) {
        this(taskExecutionId, processId, workflowDefinitionId, stepId, taskId, variables, null);
    }

    @Override
    public String partitionKey() {
        return processId;
    }
}
