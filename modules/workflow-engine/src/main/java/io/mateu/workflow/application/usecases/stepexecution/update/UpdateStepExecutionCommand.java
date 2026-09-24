package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;
import java.util.Map;

public record UpdateStepExecutionCommand(
        String stepId,
        List<Variable> variables,
        String log,
        StepExecutionStatus status,
        /** A failure the worker declared final: the engine does not spend the step's retries on it. */
        boolean nonRetryable
) {

    public UpdateStepExecutionCommand(String stepId, List<Variable> variables, String log, StepExecutionStatus status) {
        this(stepId, variables, log, status, false);
    }
}
