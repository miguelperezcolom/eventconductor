package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a payload template sees — the same for a REPLY, a PUBLISH_EVENT and an HTTP_CALL: every
 * process variable by name; {@code process} ({@code id}, {@code businessKey},
 * {@code workflowDefinitionId}, {@code version}); {@code step} ({@code id}, {@code name},
 * {@code executionId}); {@code now} (ISO instant); and {@code businessKey}. Plain maps, not the
 * aggregates: a template gets these values and nothing it could navigate from them.
 */
public final class TemplateContext {

    public static Map<String, Object> of(Process process, Step step, String stepExecutionId) {
        var context = new HashMap<String, Object>();
        process.getVariables().forEach(variable -> context.put(variable.name(), variable.value()));
        var processInfo = new LinkedHashMap<String, Object>();
        processInfo.put("id", process.getId());
        processInfo.put("businessKey", process.getBusinessKey());
        processInfo.put("workflowDefinitionId", process.getWorkflowDefinitionId());
        processInfo.put("version", process.getWorkflowDefinitionVersion());
        context.put("process", processInfo);
        var stepInfo = new LinkedHashMap<String, Object>();
        stepInfo.put("id", step == null ? null : step.id());
        stepInfo.put("name", step == null ? null : step.name());
        stepInfo.put("executionId", stepExecutionId);
        context.put("step", stepInfo);
        context.put("now", Instant.now().toString());
        // Last, so the canonical value wins over a variable of the same name.
        context.put("businessKey", process.getBusinessKey());
        return context;
    }

    private TemplateContext() {
    }
}
