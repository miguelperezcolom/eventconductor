package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.HttpCall;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.template.Templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an HTTP_CALL step's request from the process state — in the engine, with the same template
 * code validation uses — into the task variable the {@code http-call} worker executes. It carries
 * no secret: only the <em>names</em> of the connection and auth profile, and an inline auth block
 * whose credentials are references the worker resolves at call time.
 */
public final class HttpRequestRenderer {

    /** The task variable holding the rendered request. */
    public static final String REQUEST_VARIABLE = "__http";
    /** The taskId the built-in worker serves. */
    public static final String TASK_ID = "http-call@1";
    /** The default Kafka topic for HTTP calls (a step's {@code topic} overrides it). */
    public static final String DEFAULT_TOPIC = "http-calls";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public static String render(Step step, Process process, String stepExecutionId) {
        var http = step.http();
        if (http == null) {
            throw new IllegalArgumentException("HTTP_CALL step '" + step.id() + "' has no http block.");
        }
        var context = TemplateContext.of(process, step, stepExecutionId);
        var request = new LinkedHashMap<String, Object>();
        request.put("method", http.method() == null ? "GET" : http.method().toUpperCase());
        request.put("connection", http.connection());
        request.put("url", http.url() == null ? null : Templates.renderText(http.url(), context));
        request.put("path", http.path() == null ? null : Templates.renderText(http.path(), context));
        request.put("query", renderValues(http.query(), context));
        request.put("headers", renderValues(http.headers(), context));
        if (http.body() != null) {
            request.put("body", Templates.renderJson(http.body(), context));
            request.put("bodyKind", "json");
        } else if (http.bodyTemplate() != null) {
            request.put("body", Templates.renderText(http.bodyTemplate(), context));
            request.put("bodyKind", "text");
        }
        request.put("successStatus", http.successStatus());
        request.put("output", http.output());
        request.put("auth", http.auth());
        request.put("retryOn", http.retryOn() == null ? List.of("5xx", "io") : http.retryOn());
        request.put("idempotencyKey", stepExecutionId);
        try {
            return JSON.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("The HTTP request is not representable as JSON", e);
        }
    }

    private static Map<String, String> renderValues(Map<String, String> values, Map<String, Object> context) {
        if (values == null) {
            return null;
        }
        var rendered = new LinkedHashMap<String, String>();
        values.forEach((name, value) -> rendered.put(name, Templates.renderText(value, context)));
        return rendered;
    }

    /** Definition errors of an http block: the shared structural rules, then every template expression. */
    public static List<String> problems(Step step) {
        var where = "HTTP_CALL step '" + step.id() + "'";
        var http = step.http();
        @SuppressWarnings("unchecked")
        var asMap = http == null ? null : (Map<String, Object>) JSON.convertValue(http, Map.class);
        var problems = new ArrayList<>(io.mateu.workflow.analysis.HttpCallRules.problems(asMap, where));
        if (http == null) {
            return problems;
        }
        checkTemplate(http.url(), where + " http.url", problems);
        checkTemplate(http.path(), where + " http.path", problems);
        checkTemplate(http.query(), where + " http.query", problems);
        checkTemplate(http.headers(), where + " http.headers", problems);
        checkTemplate(http.body(), where + " http.body", problems);
        checkTemplate(http.bodyTemplate(), where + " http.bodyTemplate", problems);
        if (http.output() != null) {
            http.output().forEach((variable, expression) ->
                    checkTemplate("${" + expression + "}", where + " http.output." + variable, problems));
        }
        return problems;
    }

    private static void checkTemplate(Object template, String where, List<String> problems) {
        // Secret references are the worker's to resolve; they are not expressions to parse here (the
        // structural rules already confined them to inline auth credentials).
        if (template != null && !String.valueOf(template).contains("${secret:")) {
            problems.addAll(Templates.problems(template, where));
        }
    }

    private HttpRequestRenderer() {
    }
}
