package io.mateu.workflow.application.usecases.stepexecution.complete;

import java.util.Map;

public record CompleteStepExecutionCommand(
        String stepId,
        Map<String, Object> variables,
        String log
) {
}
