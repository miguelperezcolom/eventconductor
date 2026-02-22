package io.mateu.workflow.domain;

public record WorkflowDefinition(
        String id,
        String name,
        String description,
        int version,
        WorkflowDefinitionStatus status
) {
}
