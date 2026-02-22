package io.mateu.workflow.domain;

import java.util.Map;

public record Step(
        String id,
        String workflowDefinitionId,
        String name,
        String description,
        Map<String, Object> variables
) {
}
