package io.mateu.workflow.domain;

import java.util.Map;

public record Step(
        String id,
        String workflowDefinitionId,
        StepType type,
        String name,
        String description,
        Map<String, Object> variables,
        boolean rollbackable,
        long timeout,
        int retries
) {
}
