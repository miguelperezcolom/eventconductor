package io.mateu.workflow.domain;

import java.util.Map;

public record Step(
        String id,
        String workflowDefinitionId,
        StepType type,
        StepPrecondition precondition,
        String name,
        String description,
        Map<String, Object> variables,
        boolean rollbackable,
        long timeout,
        int retries,
        String compensationStepId
) {
}
