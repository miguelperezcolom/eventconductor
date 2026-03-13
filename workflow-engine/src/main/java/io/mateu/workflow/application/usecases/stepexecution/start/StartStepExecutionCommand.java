package io.mateu.workflow.application.usecases.stepexecution.start;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

public record StartStepExecutionCommand(
        String stepExecutionId
) {
}
