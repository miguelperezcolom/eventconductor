package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

/**
 * @param nonRetryable a failure the worker declared final: the engine does not spend the step's
 *                     retries on it
 * @param attempt      which dispatch of the step the report is about (echoed from the task), or
 *                     null when the reporter did not say — then it is applied unchecked. A report
 *                     for any other attempt than the step's current one is a late reply from an
 *                     attempt that was abandoned (restart, retry after a timeout) and is dropped.
 */
public record UpdateStepExecutionCommand(
        String stepId,
        List<Variable> variables,
        String log,
        StepExecutionStatus status,
        boolean nonRetryable,
        Integer attempt
) {

    public UpdateStepExecutionCommand(String stepId, List<Variable> variables, String log, StepExecutionStatus status,
                                      boolean nonRetryable) {
        this(stepId, variables, log, status, nonRetryable, null);
    }

    public UpdateStepExecutionCommand(String stepId, List<Variable> variables, String log, StepExecutionStatus status) {
        this(stepId, variables, log, status, false, null);
    }
}
