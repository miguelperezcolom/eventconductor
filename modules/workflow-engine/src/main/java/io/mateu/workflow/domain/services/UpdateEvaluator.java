package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.JEXLEvaluator;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.UpdateDefinition;
import io.mateu.workflow.domain.aggregates.Variable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what an update does to a process, without doing it: the guards, the variables it writes and
 * the result it answers. A pure function of the update's declaration, the process as it is and the
 * payload, so the caller can apply the outcome — or nothing — in one transaction.
 *
 * <p>{@code validate} and {@code set} see the process <em>before</em> the update; {@code reply} (or
 * {@code replyTemplate}) sees it <em>after</em>, so a result can report what the update made of it. A guard
 * that cannot be evaluated refuses (fail closed, as every guard in the engine); a {@code set} or a reply
 * that cannot be evaluated fails the update — in both cases nothing is written.
 */
public final class UpdateEvaluator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What applying the update would do. */
    public sealed interface Outcome permits Applied, Refused, Failed {
    }

    /** Accepted: write {@code variables}, answer {@code result} (JSON). */
    public record Applied(List<Variable> variables, String result) implements Outcome {
    }

    /** A guard said no. */
    public record Refused(String message) implements Outcome {
    }

    /** An expression could not be evaluated: the definition's fault, not the caller's. */
    public record Failed(String message) implements Outcome {
    }

    public static Outcome evaluate(UpdateDefinition update, Process process, Object payload, long maxResultBytes) {
        var before = contextOf(process.getVariables(), process, payload);
        if (update.validate() != null) {
            for (var validation : update.validate()) {
                Object value;
                try {
                    value = JEXLEvaluator.eval(validation.expression(), before);
                } catch (RuntimeException e) {
                    value = null;
                }
                if (!Boolean.TRUE.equals(value)) {
                    return new Refused(validation.message() != null && !validation.message().isBlank()
                            ? validation.message()
                            : "Update '" + update.name() + "' refused: '" + validation.expression() + "' does not hold.");
                }
            }
        }
        var written = new ArrayList<Variable>();
        if (update.set() != null) {
            for (var entry : update.set().entrySet()) {
                try {
                    var value = JEXLEvaluator.evaluate(entry.getValue(), before).value();
                    written.add(new Variable(entry.getKey(), textOf(value)));
                } catch (RuntimeException e) {
                    return new Failed("set." + entry.getKey() + " ('" + entry.getValue() + "') could not be evaluated: "
                            + e.getMessage());
                }
            }
        }
        var after = new ArrayList<>(process.getVariables());
        written.forEach(variable -> {
            after.removeIf(existing -> existing.name().equals(variable.name()));
            after.add(variable);
        });
        String result;
        try {
            if (update.replyTemplate() != null) {
                var candidate = process.withVariables(after);
                result = io.mateu.workflow.template.Templates.renderJson(update.replyTemplate(),
                        TemplateContext.of(candidate, null, null, scopeOf(payload)));
            } else if (update.reply() != null) {
                result = JSON.writeValueAsString(JEXLEvaluator.evaluate(update.reply(), contextOf(after, process, payload)).value());
            } else {
                result = "{}";
            }
        } catch (Exception e) {
            return new Failed("The reply of update '" + update.name() + "' could not be computed: " + e.getMessage());
        }
        if (result == null) {
            result = "null";
        }
        var size = result.getBytes(StandardCharsets.UTF_8).length;
        if (maxResultBytes > 0 && size > maxResultBytes) {
            return new Failed("The reply of update '" + update.name() + "' is " + size + " bytes, over the "
                    + maxResultBytes + "-byte limit (workflow.sync.max-reply-bytes).");
        }
        return new Applied(written, result);
    }

    /** The payload as the expressions see it: an object is a map, an array a list, JSON text parsed. */
    public static Object payloadOf(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("The payload is not JSON: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> scopeOf(Object payload) {
        var scope = new HashMap<String, Object>();
        scope.put("payload", payload);
        return scope;
    }

    private static Map<String, Object> contextOf(List<Variable> variables, Process process, Object payload) {
        var context = new HashMap<String, Object>();
        context.put("process", process);
        variables.forEach(variable -> context.put(variable.name(), variable.value()));
        context.put("payload", payload);
        // Last, so the canonical value wins over a variable of the same name.
        context.put("businessKey", process.getBusinessKey());
        return context;
    }

    /** Variables are text: a string stays itself, anything else is kept as its JSON. */
    static String textOf(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * The payload as recorded and shown: every top-level field the update declares {@code sensitive}
     * replaced by the mask. Not JSON (or not an object) → kept as it is when nothing is sensitive, masked
     * whole otherwise.
     */
    public static String masked(UpdateDefinition update, String payloadJson) {
        if (payloadJson == null || update == null || update.sensitive() == null) {
            return payloadJson;
        }
        try {
            var tree = JSON.readTree(payloadJson);
            if (!tree.isObject()) {
                return "\"" + MASK + "\"";
            }
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            var masked = new LinkedHashMap<String, Object>();
            object.properties().forEach(entry -> masked.put(entry.getKey(),
                    update.isSensitive(entry.getKey()) ? MASK : entry.getValue()));
            return JSON.writeValueAsString(masked);
        } catch (Exception e) {
            return "\"" + MASK + "\"";
        }
    }

    /** What a masked value reads as. */
    public static final String MASK = "****";

    private UpdateEvaluator() {
    }
}
