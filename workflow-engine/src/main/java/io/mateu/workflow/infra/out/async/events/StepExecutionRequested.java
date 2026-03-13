package io.mateu.workflow.infra.out.async.events;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

public record StepExecutionRequested(
        String stepExecutionId,
        String processId,
        String workflowDefinitionId,
        String stepId,
        List<Variable>variables
) {
}
