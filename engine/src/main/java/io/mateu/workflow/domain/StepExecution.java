package io.mateu.workflow.domain;

import java.util.Map;

public record StepExecution(
        String id,
        String workflowDefinitionId,
        String stepId,
        Map<String, Object> variables,
        StepExecutionStatus status,
        String workerId
) {
}
