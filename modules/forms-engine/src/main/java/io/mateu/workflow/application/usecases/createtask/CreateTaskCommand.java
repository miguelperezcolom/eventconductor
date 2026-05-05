package io.mateu.workflow.application.usecases.createtask;

import io.mateu.workflow.domain.Variable;

import java.util.List;

public record CreateTaskCommand(
        String stepExecutionId,
        String processId,
        String workflowDefinitionId,
        String stepId,
        String formId,
        List<Variable> variables
) {
}
