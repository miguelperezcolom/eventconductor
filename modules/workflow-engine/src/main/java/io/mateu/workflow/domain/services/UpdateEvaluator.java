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
        return evaluate(update, process, payload, maxResultBytes, null);
    }

    /**
     * The same, keeping the variables {@code sensitive} names out of what answers the caller — the guards and
     * the reply — unless the update opts in ({@code allowSensitiveVariables}). {@code set} sees them all.
     */
    public static Outcome evaluate(UpdateDefinition update, Process process, Object payload, long maxResultBytes,
                                   java.util.function.Predicate<String> sensitive) {
        var hide = sensitive != null && !update.allowsSensitiveVariables();
        var all = contextOf(process.getVariables(), process, payload);
        var before = hide ? contextOf(visible(process.getVariables(), sensitive), process, payload) : all;
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
                    var value = JEXLEvaluator.evaluate(entry.getValue(), all).value();
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
        var shown = hide ? visible(after, sensitive) : after;
        String result;
        try {
            if (update.replyTemplate() != null) {
                var candidate = process.withVariables(shown);
                result = io.mateu.workflow.template.Templates.renderJson(update.replyTemplate(),
                        TemplateContext.of(candidate, null, null, scopeOf(payload)));
            } else if (update.reply() != null) {
                result = JSON.writeValueAsString(JEXLEvaluator.evaluate(update.reply(), contextOf(shown, process, payload)).value());
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

    private static List<Variable> visible(List<Variable> variables, java.util.function.Predicate<String> sensitive) {
        return variables.stream().filter(variable -> !sensitive.test(variable.name())).toList();
    }

    /** What a masked value reads as — the same mask as every sensitive value the engine shows. */
    public static final String MASK = io.mateu.workflow.analysis.SensitiveVariableRules.MASK;

    private static final java.util.regex.Pattern PAYLOAD_FIELD =
            java.util.regex.Pattern.compile("payload\\s*(?:\\.\\s*([A-Za-z_$][A-Za-z0-9_$]*)|\\[\\s*['\"]([^'\"]+)['\"]\\s*])");

    /**
     * The top-level payload fields that must never be kept or carried in clear: the ones the update declares
     * {@code sensitive}, the ones named like a sensitive variable, and the ones a {@code set} writes into a
     * sensitive variable ({@code cardNumber: payload.card} protects {@code card}).
     */
    public static java.util.Set<String> protectedFields(UpdateDefinition update, java.util.function.Predicate<String> sensitive,
                                                       java.util.Collection<String> fields) {
        var protectedFields = new java.util.LinkedHashSet<String>();
        if (update == null) {
            return protectedFields;
        }
        if (update.sensitive() != null) {
            protectedFields.addAll(update.sensitive());
        }
        if (sensitive != null) {
            fields.stream().filter(sensitive).forEach(protectedFields::add);
            if (update.set() != null) {
                update.set().forEach((variable, expression) -> {
                    if (variable != null && expression != null && sensitive.test(variable)) {
                        var matcher = PAYLOAD_FIELD.matcher(expression);
                        while (matcher.find()) {
                            protectedFields.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                        }
                    }
                });
            }
        }
        return protectedFields;
    }

    /**
     * The payload with its protected fields sealed under the active key — how it is recorded and carried to
     * the process's owner. Without encryption the fields are masked instead (the record then cannot carry
     * them; the command carries the payload as it came, inside the engine). A payload that is not a JSON
     * object is sealed (or masked) whole when the update declares anything sensitive.
     */
    public static String seal(UpdateDefinition update, String payloadJson, java.util.function.Predicate<String> sensitive,
                              io.mateu.workflow.crypto.VariableEncryption encryption) {
        if (payloadJson == null || update == null) {
            return payloadJson;
        }
        var enabled = encryption != null && encryption.enabled();
        try {
            var tree = JSON.readTree(payloadJson);
            if (!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
                var anything = update.sensitive() != null || (sensitive != null && update.set() != null
                        && update.set().keySet().stream().anyMatch(sensitive));
                return !anything ? payloadJson
                        : JSON.writeValueAsString(enabled ? encryption.seal(payloadJson) : MASK);
            }
            var fields = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            var protectedFields = protectedFields(update, sensitive, fields);
            var changed = false;
            for (var field : fields) {
                if (protectedFields.contains(field)) {
                    var value = object.get(field);
                    object.put(field, enabled ? encryption.seal(JSON.writeValueAsString(value)) : MASK);
                    changed = true;
                }
            }
            return changed ? JSON.writeValueAsString(object) : payloadJson;
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return update.sensitive() == null ? payloadJson : "\"" + MASK + "\"";
        }
    }

    /** A payload {@link #seal sealed} for the record or the command, opened back to what the caller sent. */
    public static String open(String payloadJson, io.mateu.workflow.crypto.VariableEncryption encryption) {
        if (!io.mateu.workflow.crypto.SensitivePayloads.containsSealed(payloadJson) || encryption == null) {
            return payloadJson;
        }
        try {
            var tree = JSON.readTree(payloadJson);
            if (tree.isTextual()) {
                return encryption.open(tree.asText());
            }
            if (tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                var fields = new java.util.ArrayList<String>();
                object.fieldNames().forEachRemaining(fields::add);
                for (var field : fields) {
                    var value = object.get(field);
                    if (value.isTextual() && io.mateu.workflow.crypto.VariableEnvelope.isSealed(value.asText())) {
                        object.set(field, JSON.readTree(encryption.open(value.asText())));
                    }
                }
                return JSON.writeValueAsString(object);
            }
            return payloadJson;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("The payload is not JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** A recorded payload or result as every door shows it: each sealed value — or a sealed whole — masked. */
    public static String shown(String json) {
        if (!io.mateu.workflow.crypto.SensitivePayloads.containsSealed(json)) {
            return json;
        }
        try {
            var tree = JSON.readTree(json);
            if (tree.isTextual()) {
                return JSON.writeValueAsString(MASK);
            }
            maskSealed(tree);
            return JSON.writeValueAsString(tree);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return JSON.valueToTree(MASK).toString();
        }
    }

    private static void maskSealed(com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var fields = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (var field : fields) {
                var child = object.get(field);
                if (child.isTextual() && io.mateu.workflow.crypto.VariableEnvelope.isSealed(child.asText())) {
                    object.put(field, MASK);
                } else {
                    maskSealed(child);
                }
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                var child = array.get(i);
                if (child.isTextual() && io.mateu.workflow.crypto.VariableEnvelope.isSealed(child.asText())) {
                    array.set(i, MASK);
                } else {
                    maskSealed(child);
                }
            }
        }
    }

    private UpdateEvaluator() {
    }
}
