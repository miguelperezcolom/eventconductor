package io.mateu.workflow.domain.aggregates;

public record WokflowDefinitionVersion(
        String id,
        String workflowDefinitionId,
        int version,
        String json
) {
}
