package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

public record CreateProcessCommand(
        String processId,
        String workflowDefinitionId,
        String businessKey,
        List<Variable> variables,
        /** Id of the parent PROCESS step execution that spawned this process; null for top-level processes. */
        String parentStepExecutionId
) {
}
