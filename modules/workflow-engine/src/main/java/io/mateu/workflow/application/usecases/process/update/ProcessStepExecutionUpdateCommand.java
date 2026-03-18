package io.mateu.workflow.application.usecases.process.update;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.util.Map;

public record ProcessStepExecutionUpdateCommand(
        String processId
) {
}
