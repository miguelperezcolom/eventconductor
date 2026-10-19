package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.template.Templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an LLM_CALL step's request from the process state — in the engine, with the same template
 * code validation uses — into the task variable the {@code llm-call} worker executes: the connection
 * <em>name</em>, the model override, the rendered system prompt and prompt, the sampling options, the
 * response schema and where the answer goes. Never a key: the connection's credentials are the worker's
 * configuration.
 */
public final class LlmRequestRenderer {

    /** The task variable holding the rendered request. */
    public static final String REQUEST_VARIABLE = "__llm";
    /** The taskId the built-in worker serves. */
    public static final String TASK_ID = "llm-call@1";
    /** The default Kafka topic for LLM calls (a step's {@code topic} overrides it). */
    public static final String DEFAULT_TOPIC = "llm-calls";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public static String render(Step step, Process process, String stepExecutionId) {
        return render(step, process, stepExecutionId, null);
    }

    /** The same, rendered with a forEach iteration's typed scope laid over the variables. */
    public static String render(Step step, Process process, String stepExecutionId, Map<String, Object> scope) {
        var llm = step.llm();
        if (llm == null) {
            throw new IllegalArgumentException("LLM_CALL step '" + step.id() + "' has no llm block.");
        }
        // A prompt leaves the engine for the model provider: sensitive variables stay out of its context
        // unless the step opts in (llm.allowSensitiveVariables). A prompt naming one is refused at load; this
        // also keeps them out of a prompt that reaches them indirectly.
        if (!llm.allowsSensitiveVariables()) {
            var sensitive = io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process);
            if (!sensitive.isEmpty() && process.getVariables() != null) {
                process = process.withVariables(process.getVariables().stream()
                        .filter(variable -> variable == null || !sensitive.test(variable.name()))
                        .toList());
            }
        }
        var context = TemplateContext.of(process, step, stepExecutionId, scope);
        var request = new LinkedHashMap<String, Object>();
        request.put("connection", llm.connection());
        request.put("model", llm.model());
        request.put("system", llm.system() == null ? null : Templates.renderText(llm.system(), context));
        request.put("prompt", Templates.renderText(llm.prompt(), context));
        request.put("temperature", llm.temperature());
        request.put("maxTokens", llm.maxTokens());
        request.put("responseSchema", llm.responseSchema());
        request.put("output", llm.output());
        request.put("outputVariable", llm.outputVariable());
        request.put("usageVariable", llm.usageVariable());
        try {
            return JSON.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("The LLM request is not representable as JSON", e);
        }
    }

    /** Definition errors of an llm block: the shared structural rules, then every template and output expression. */
    public static List<String> problems(Step step) {
        var where = "LLM_CALL step '" + step.id() + "'";
        var llm = step.llm();
        @SuppressWarnings("unchecked")
        var asMap = llm == null ? null : (Map<String, Object>) JSON.convertValue(llm, Map.class);
        if (asMap != null) {
            asMap.values().removeIf(java.util.Objects::isNull);
        }
        var problems = new ArrayList<>(io.mateu.workflow.analysis.LlmCallRules.problems(asMap, where));
        if (llm == null) {
            return problems;
        }
        checkTemplate(llm.system(), where + " llm.system", problems);
        checkTemplate(llm.prompt(), where + " llm.prompt", problems);
        if (llm.output() != null) {
            llm.output().forEach((variable, expression) ->
                    checkTemplate("${" + expression + "}", where + " llm.output." + variable, problems));
        }
        return problems;
    }

    private static void checkTemplate(Object template, String where, List<String> problems) {
        // A ${secret:…} reference is refused by the structural rules; it is not an expression to parse.
        if (template != null && !String.valueOf(template).contains("${secret:")) {
            problems.addAll(Templates.problems(template, where));
        }
    }

    private LlmRequestRenderer() {
    }
}
