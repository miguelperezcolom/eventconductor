package io.mateu.workflow.application.usecases.process.stepover;

import java.util.Map;

public record StepOverProcessCommand(
        String workflowDefinitionId,
        Map<String, Object> variables
) {
}
