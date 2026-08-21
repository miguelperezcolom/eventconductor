package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDateTime;

/**
 * What analytics needs to know about a step execution: whose it is, which step, how it ended, in
 * what order it ran and between which two moments. Emphatically not the step JSON or the
 * variables — those two columns are the bulk of the engine's largest table.
 *
 * <p>{@code definitionId} is the step execution's own, stamped when the engine dispatched it from
 * its process's definition. Grouping by it rather than by the process's saves the report a join it
 * would otherwise need in both stores — and the in-memory one cannot do joins at all, holding no
 * processes.
 *
 * @see StepExecutionRepository#findAnalyticsRows
 */
public record StepExecutionAnalyticsRow(
        String processId,
        String definitionId,
        String stepId,
        StepExecutionStatus status,
        long order,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public static StepExecutionAnalyticsRow from(StepExecution stepExecution) {
        return new StepExecutionAnalyticsRow(
                stepExecution.getProcessId(),
                stepExecution.getWorkflowDefinitionId(),
                stepExecution.getStepId(),
                stepExecution.getStatus(),
                stepExecution.getOrder(),
                stepExecution.getStartedAt(),
                stepExecution.getFinishedAt());
    }
}
