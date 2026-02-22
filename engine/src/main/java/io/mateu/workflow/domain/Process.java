package io.mateu.workflow.domain;

import java.util.Map;

public record Process(
        String id,
        String businessKey,
        Map<String, Object> variables,
        ProcessStatus status,
        int completionPercentage
) {
}
