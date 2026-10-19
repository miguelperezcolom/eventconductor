package io.mateu.workflow.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Seals and opens the variables inside a JSON document — a stored variables column, an outbox payload,
 * a message on the wire — without knowing its type.
 *
 * <p>A variable is any object with a textual {@code name} and a textual {@code value} (the shape of
 * {@code Variable} in every event and row), wherever it sits in the document: {@code variables},
 * {@code set}, {@code outputVariables}… — and every entry of a {@code variableMapping} object (name → value,
 * as a migration request carries them). Sealing replaces the value of those whose name is sensitive;
 * opening replaces every sealed text value anywhere, so it needs no policy.
 */
public final class SensitivePayloads {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SensitivePayloads() {
    }

    /** Whether the document carries any sealed value (a cheap test, before parsing). */
    public static boolean containsSealed(String json) {
        return json != null && json.contains(VariableEnvelope.PREFIX);
    }

    /** {@code json} with the value of every sensitive variable sealed; unchanged when there is nothing to seal. */
    public static String sealVariables(String json, Predicate<String> sensitive, VariableEncryption encryption) {
        if (json == null || json.isBlank() || sensitive == null
                || (sensitive instanceof SensitiveNames names && names.isEmpty())) {
            return json;
        }
        try {
            var root = JSON.readTree(json);
            return transform(root, sensitive, encryption::seal) ? JSON.writeValueAsString(root) : json;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read a payload to seal its sensitive variables: " + e.getClass().getSimpleName());
        }
    }

    /** {@code json} with every sealed value opened; unchanged when it carries none. */
    public static String open(String json, VariableEncryption encryption) {
        if (!containsSealed(json)) {
            return json;
        }
        try {
            var root = JSON.readTree(json);
            if (root.isTextual()) {
                // The document is itself a sealed string (a JSON string literal).
                return JSON.writeValueAsString(encryption.open(root.asText()));
            }
            return openNode(root, encryption) ? JSON.writeValueAsString(root) : json;
        } catch (java.io.IOException e) {
            // Not JSON: a sealed blob is the whole text.
            return encryption.open(json);
        }
    }

    /** Seals the variables of an already parsed tree in place; whether anything changed. */
    public static boolean sealVariablesIn(JsonNode root, Predicate<String> sensitive, VariableEncryption encryption) {
        return transform(root, sensitive, encryption::seal);
    }

    /** Opens every sealed value of an already parsed tree in place; whether anything changed. */
    public static boolean openNode(JsonNode node, VariableEncryption encryption) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (var field : names) {
                var child = object.get(field);
                if (child != null && child.isTextual() && VariableEnvelope.isSealed(child.asText())) {
                    object.set(field, TextNode.valueOf(encryption.open(child.asText())));
                    changed = true;
                } else if (child != null && child.isContainerNode()) {
                    changed |= openNode(child, encryption);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                var child = array.get(i);
                if (child.isTextual() && VariableEnvelope.isSealed(child.asText())) {
                    array.set(i, TextNode.valueOf(encryption.open(child.asText())));
                    changed = true;
                } else if (child.isContainerNode()) {
                    changed |= openNode(child, encryption);
                }
            }
        }
        return changed;
    }

    private static boolean transform(JsonNode node, Predicate<String> sensitive, UnaryOperator<String> seal) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            var name = object.get("name");
            var value = object.get("value");
            if (name != null && name.isTextual() && value != null && value.isTextual()
                    && !VariableEnvelope.isSealed(value.asText()) && sensitive.test(name.asText())) {
                object.set("value", TextNode.valueOf(seal.apply(value.asText())));
                changed = true;
            }
            if (object.get("variableMapping") instanceof ObjectNode mapping) {
                var names = new java.util.ArrayList<String>();
                mapping.fieldNames().forEachRemaining(names::add);
                for (var mapped : names) {
                    var mappedValue = mapping.get(mapped);
                    if (mappedValue.isTextual() && !VariableEnvelope.isSealed(mappedValue.asText()) && sensitive.test(mapped)) {
                        mapping.set(mapped, TextNode.valueOf(seal.apply(mappedValue.asText())));
                        changed = true;
                    }
                }
            }
            for (var it = object.elements(); it.hasNext(); ) {
                var child = it.next();
                if (child.isContainerNode()) {
                    changed |= transform(child, sensitive, seal);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (var child : array) {
                if (child.isContainerNode()) {
                    changed |= transform(child, sensitive, seal);
                }
            }
        }
        return changed;
    }
}
