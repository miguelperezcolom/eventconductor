package io.mateu.workflow.domain;

public record WokflowDefinitionVersion(
        String id,
        String workflowDefinitionId,
        int version,
        String json
) {
}
