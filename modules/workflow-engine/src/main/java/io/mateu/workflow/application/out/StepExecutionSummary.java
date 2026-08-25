package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDateTime;

/**
 * The handful of fields a step-execution listing actually paints — no step JSON, no variables.
 * Those two columns are the bulk of {@code step_execution_entity}, and a listing that loads whole
 * {@link StepExecution} aggregates pays for them on every row it never shows.
 *
 * @see StepExecutionRepository#searchSummaries
 */
public record StepExecutionSummary(
        String id,
        String processId,
        String stepId,
        StepExecutionStatus status,
        LocalDateTime startedAt,
        int attemptCount) {

    public static StepExecutionSummary from(StepExecution stepExecution) {
        return new StepExecutionSummary(
                stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getStepId(),
                stepExecution.getStatus(),
                stepExecution.getStartedAt(),
                stepExecution.getAttemptCount());
    }
}
