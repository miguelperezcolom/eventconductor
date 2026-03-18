package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.util.Map;

public record UpdateStepExecutionCommand(
        String stepId,
        Map<String, Object> variables,
        String log,
        StepExecutionStatus status
) {
}
