package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;

import static io.mateu.workflow.application.services.JEXLEvaluator.evaluate;

/**
 * Computes what a REPLY step answers, as JSON.
 *
 * <p>{@code replyVariables} → an object with one member per listed variable (a variable that is not
 * set is present as null, so the caller sees the shape it was promised). {@code replyExpression} →
 * the value of a JEXL expression, evaluated with the same context every other expression in the
 * engine gets: {@code process}, each variable, and {@code businessKey} last. Neither → {@code {}}.
 *
 * <p>Unlike a guard, a reply that cannot be computed is not silently falsy: the caller is waiting
 * for this value, so the step fails and the process's failure contract answers instead.
 */
public final class ReplyPayloadResolver {

    /** Compact, not pretty-printed: this is a wire value the caller receives verbatim. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** The payload as JSON, or why it could not be computed. Exactly one of the two is non-null. */
    public record Result(String json, String error) {
        static Result ok(String json) {
            return new Result(json, null);
        }

        static Result failed(String error) {
            return new Result(null, error);
        }
    }

    public static Result resolve(Step step, Process process, long maxBytes) {
        var variables = step.replyVariables();
        var expression = step.replyExpression();
        boolean hasVariables = variables != null && !variables.isEmpty();
        boolean hasExpression = expression != null && !expression.isBlank();
        boolean hasTemplate = step.replyTemplate() != null;
        if ((hasVariables ? 1 : 0) + (hasExpression ? 1 : 0) + (hasTemplate ? 1 : 0) > 1) {
            return Result.failed("REPLY step '" + step.id()
                    + "' declares more than one of replyVariables, replyExpression and replyTemplate; it must declare one.");
        }
        String json;
        if (hasTemplate) {
            try {
                json = io.mateu.workflow.template.Templates.renderJson(step.replyTemplate(), TemplateContext.of(process, step, null));
            } catch (io.mateu.workflow.template.Templates.TemplateException e) {
                return Result.failed("replyTemplate could not be rendered: " + e.getMessage());
            }
        } else if (hasExpression) {
            try {
                json = toJson(evaluate(expression, contextOf(process)).value());
            } catch (Exception e) {
                return Result.failed("replyExpression '" + expression + "' could not be evaluated: "
                        + e.getMessage());
            }
        } else if (hasVariables) {
            var reply = new LinkedHashMap<String, Object>();
            variables.forEach(name -> reply.put(name, valueOf(process, name)));
            json = toJson(reply);
        } else {
            json = "{}";
        }
        if (json == null) {
            json = "null";
        }
        var size = json.getBytes(StandardCharsets.UTF_8).length;
        if (maxBytes > 0 && size > maxBytes) {
            return Result.failed("The reply is " + size + " bytes, over the " + maxBytes
                    + "-byte limit (workflow.sync.max-reply-bytes).");
        }
        return Result.ok(json);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the value is not representable as JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static Object valueOf(Process process, String name) {
        return process.getVariables().stream()
                .filter(variable -> name.equals(variable.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value)
                .findFirst()
                .orElse(null);
    }

    private static HashMap<String, Object> contextOf(Process process) {
        var context = new HashMap<String, Object>();
        context.put("process", process);
        process.getVariables().forEach(variable -> context.put(variable.name(), variable.value()));
        // Seeded after the variables so the canonical value wins, as in LockKeyResolver and
        // MessageCorrelation.
        context.put("businessKey", process.getBusinessKey());
        return context;
    }

    private ReplyPayloadResolver() {
    }
}
