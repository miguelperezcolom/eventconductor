package io.mateu.workflow.application.httpworkers;

import io.mateu.workflow.dtos.Variable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A task held for a worker over HTTP: what the engine dispatched ({@code TaskExecutionRequested}),
 * plus the lease of whoever is working on it.
 *
 * @param id                   the step execution id — the task's identity across redeliveries
 * @param attempt              which dispatch of the step this is (the engine's {@code dispatchCount});
 *                             a reply must echo it
 * @param createdAt            when this dispatch was queued; tasks are handed out oldest first
 * @param leaseUntil           null while nobody holds it; a past instant is an expired lease, which
 *                             makes the task available again
 * @param workerId             who holds (or last held) the lease
 * @param leaseToken           identifies the current lease; a heartbeat must present it
 * @param deliveries           how many times it has been leased; above one it was redelivered
 * @param cancelled            the engine cancelled it while a worker held it; the next heartbeat says so
 */
public record HttpTask(
        String id,
        String processId,
        String workflowDefinitionId,
        String stepId,
        String taskId,
        String topic,
        List<Variable> variables,
        Integer attempt,
        LocalDateTime createdAt,
        LocalDateTime leaseUntil,
        String workerId,
        String leaseToken,
        int deliveries,
        boolean cancelled) {

    public HttpTask {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }

    /** A freshly dispatched task, not leased. */
    public static HttpTask queued(String id, String processId, String workflowDefinitionId, String stepId,
                                  String taskId, String topic, List<Variable> variables, Integer attempt,
                                  LocalDateTime createdAt) {
        return new HttpTask(id, processId, workflowDefinitionId, stepId, taskId, topic, variables, attempt,
                createdAt, null, null, null, 0, false);
    }

    /** The attempt as a number: a task from an engine that did not send one reads as 0. */
    public int attemptOrZero() {
        return attempt == null ? 0 : attempt;
    }

    public boolean leasedAt(LocalDateTime now) {
        return leaseUntil != null && leaseUntil.isAfter(now);
    }

    public HttpTask leased(String workerId, String leaseToken, LocalDateTime until) {
        return new HttpTask(id, processId, workflowDefinitionId, stepId, taskId, topic, variables, attempt,
                createdAt, until, workerId, leaseToken, deliveries + 1, cancelled);
    }

    public HttpTask withLeaseUntil(LocalDateTime until) {
        return new HttpTask(id, processId, workflowDefinitionId, stepId, taskId, topic, variables, attempt,
                createdAt, until, workerId, leaseToken, deliveries, cancelled);
    }

    public HttpTask markedCancelled() {
        return new HttpTask(id, processId, workflowDefinitionId, stepId, taskId, topic, variables, attempt,
                createdAt, leaseUntil, workerId, leaseToken, deliveries, true);
    }
}
