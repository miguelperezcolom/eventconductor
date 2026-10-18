package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * A worker reporting on the task it was given.
 *
 * @param processId echoed back from the {@link TaskExecutionRequested} the worker received, so
 *                  the reply can be routed to the pod that owns the process. Appended to the
 *                  record: a worker built against an older shared module leaves it null, and the
 *                  event then falls back to the unrouted behaviour rather than failing to
 *                  deserialize.
 * @param attempt   echoed back from {@link TaskExecutionRequested#attempt()}: which dispatch of the
 *                  step this reply is about, so a late reply from an abandoned attempt (a process
 *                  restarted, a step retried after a timeout) is not taken for the running one's.
 *                  Null from a worker that does not echo it, and such a reply is applied unchecked,
 *                  as before.
 */
public record TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables,
                                String processId, boolean nonRetryable, Integer attempt) implements DomainEvent {

    /** The shape before the attempt was echoed: the reply is applied without the attempt check. */
    public TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables,
                             String processId, boolean nonRetryable) {
        this(taskExecutionId, status, variables, processId, nonRetryable, null);
    }

    /**
     * The shape before a worker could declare a failure final ({@code nonRetryable}: the engine does
     * not spend the step's retries on it — see {@code FailureMarkers}). Older workers' replies read
     * as retryable.
     */
    public TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables,
                             String processId) {
        this(taskExecutionId, status, variables, processId, false, null);
    }

    /**
     * For workers that do not know the process — a third-party worker built against an older
     * shared module, or one that only kept the task id. The event then carries no key and is
     * handled by whichever pod receives it, exactly as before ownership existed.
     */
    public TaskStatusChanged(String taskExecutionId, TaskStatus status, List<Variable> variables) {
        this(taskExecutionId, status, variables, null, false, null);
    }

    @Override
    public String partitionKey() {
        return processId;
    }
}
