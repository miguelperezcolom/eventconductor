package io.mateu.workflow.domain;

import io.mateu.core.infra.declarative.Entity;

import java.util.Map;

public record StepExecution(
        String id,
        String processId,
        String workflowDefinitionId,
        String stepId,
        Map<String, Object> variables,
        StepExecutionStatus status,
        String workerId
) implements Entity<String> {
}
