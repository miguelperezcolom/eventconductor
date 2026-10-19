package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Where the incidents view reads from: the steps that failed in processes that failed, with what they
 * last said about it.
 *
 * <p>An <em>incident</em> is a failure an operator can act on — a step execution in ERROR (or in a
 * TIMEOUT that does not route anywhere) of a process that is ERROR, i.e. stopped on it and waiting for
 * someone. A failed saga that has rolled back (COMPENSATED) or halted (COMPENSATION_FAILED) is
 * finished and is not one; neither is a step that failed and was retried since. Only current
 * executions count: a loop's superseded pass is history, and a failed forEach is one incident on its
 * container (its failing iteration's message is the container's error line), not one per iteration.
 *
 * <p>Two implementations, chosen by {@code workflow.persistence}: the in-memory one walks the store; the
 * JPA one is one indexed join (failed processes → their failed steps) plus one look-up of their error
 * lines, by process — see {@code JpaIncidentRepository}. The grouping into incidents happens above,
 * in {@code IncidentService}, the same way for both.
 */
public interface IncidentRepository {

    /**
     * One failed step of a failed process.
     *
     * @param failedAt when the step failed (its {@code finishedAt})
     * @param error    the last error line the step logged, or null when it logged none
     */
    record FailedStep(String processId, String businessKey, String workflowDefinitionId, String stepId,
                      String stepExecutionId, StepExecutionStatus status, LocalDateTime failedAt, String error) {
    }

    /**
     * The failed steps of failed processes, most recent first.
     *
     * @param workflowDefinitionId only this definition's, or every definition's when null
     * @param limit                at most this many
     */
    List<FailedStep> findFailedSteps(String workflowDefinitionId, int limit);
}
