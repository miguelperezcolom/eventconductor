package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mateu.workflow.analysis.SensitiveVariableRules;
import io.mateu.workflow.crypto.SensitiveNames;
import io.mateu.workflow.crypto.VariableEnvelope;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Variable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the console, the REST API and the MCP tools show of a process's variables: every value of a
 * sensitive variable as {@value SensitiveVariableRules#MASK}, for everyone — reading one is the explicit,
 * audited reveal ({@link SensitiveVariableRevealService}), never a side effect of opening a page.
 *
 * <p>A pure function of the process (its definition snapshot says what is sensitive), so every door masks
 * the same way without a bean to inject. A value still sealed (a key that is gone) is masked too.
 */
public final class SensitiveVariableMasking {

    /** What a masked value reads as. */
    public static final String MASK = SensitiveVariableRules.MASK;

    private static final ObjectMapper JSON = new ObjectMapper();

    private SensitiveVariableMasking() {
    }

    /** The sensitive variables of the process's definition version. */
    public static SensitiveNames namesOf(Process process) {
        return process == null ? SensitiveNames.NONE : SensitiveVariablePolicies.ofSnapshot(
                process.getWorkflowDefinitionId(), process.getWorkflowDefinitionVersion(),
                process.getWorkflowDefinitionJson());
    }

    /** Whether {@code name} is a sensitive variable of the process. */
    public static boolean isSensitive(Process process, String name) {
        return namesOf(process).test(name);
    }

    /** One value as shown. */
    public static String shown(String name, String value, SensitiveNames names) {
        if (value == null) {
            return null;
        }
        return names.test(name) || VariableEnvelope.isSealed(value) ? MASK : value;
    }

    /** The process's variables as shown. */
    public static List<Variable> variables(Process process) {
        return mask(process == null ? null : process.getVariables(), namesOf(process));
    }

    /** Variables as shown, under these names. */
    public static List<Variable> mask(List<Variable> variables, SensitiveNames names) {
        if (variables == null) {
            return List.of();
        }
        return variables.stream()
                .map(v -> v == null ? null : new Variable(v.name(), shown(v.name(), v.value(), names)))
                .toList();
    }

    /** The process's variables as shown, by name in order (a repeated name keeps its last value). */
    public static Map<String, String> asMap(Process process) {
        var shown = new LinkedHashMap<String, String>();
        variables(process).forEach(v -> {
            if (v != null) shown.put(v.name(), v.value());
        });
        return shown;
    }

    /**
     * A JSON document (an agent's transcript) with the process's sensitive variables kept out: every value
     * under a key named like one, and every occurrence of one of their current values (4 characters or more)
     * anywhere in its text, replaced by the mask. As it is when the process has nothing sensitive.
     */
    public static String redactDocument(String json, Process process) {
        var names = namesOf(process);
        if (json == null || names.isEmpty()) {
            return json;
        }
        var text = json;
        try {
            var tree = JSON.readTree(json);
            maskKeys(tree, names);
            text = JSON.writeValueAsString(tree);
        } catch (Exception notJson) {
            // free text: only the values below
        }
        var values = process.getVariables().stream()
                .filter(v -> v != null && v.value() != null && v.value().length() >= 4 && names.test(v.name()))
                .map(Variable::value).distinct()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
        for (var value : values) {
            text = text.replace(value, MASK);
            // As it reads inside a JSON string, quotes escaped.
            var escaped = JSON.valueToTree(value).toString();
            escaped = escaped.substring(1, escaped.length() - 1);
            if (!escaped.equals(value)) {
                text = text.replace(escaped, MASK);
            }
        }
        return text;
    }

    private static void maskKeys(com.fasterxml.jackson.databind.JsonNode node, SensitiveNames names) {
        if (node instanceof ObjectNode object) {
            var fields = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (var field : fields) {
                var child = object.get(field);
                if (names.test(field)) {
                    object.put(field, MASK);
                } else if (child.isTextual() && (child.asText().trim().startsWith("{") || child.asText().trim().startsWith("["))) {
                    try {
                        var inner = JSON.readTree(child.asText());
                        maskKeys(inner, names);
                        object.put(field, JSON.writeValueAsString(inner));
                    } catch (Exception notJson) {
                        // text that only looks like JSON
                    }
                } else {
                    maskKeys(child, names);
                }
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            array.forEach(child -> maskKeys(child, names));
        }
    }

    /**
     * A JSON object (a reply payload) as shown: the members named like a sensitive variable masked. A
     * payload that is not an object is masked whole when the process has anything sensitive.
     */
    public static String json(String payload, SensitiveNames names) {
        if (payload == null || names.isEmpty()) {
            return payload;
        }
        try {
            var tree = JSON.readTree(payload);
            if (!(tree instanceof ObjectNode object)) {
                return MASK;
            }
            var fields = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (var field : fields) {
                if (names.test(field)) {
                    object.put(field, MASK);
                }
            }
            VariableProtection.mask(object, names);
            return JSON.writeValueAsString(object);
        } catch (Exception e) {
            return MASK;
        }
    }
}
