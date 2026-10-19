package io.mateu.workflow.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Moving values between a test's Java objects and the engine's text variables. */
final class Values {

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private Values() {
    }

    /** A value as the engine stores it: text as-is, anything else as JSON (what the worker SDK does). */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            return String.valueOf(value);
        }
    }

    static List<io.mateu.workflow.dtos.Variable> dtoVariables(Map<String, ?> variables) {
        var result = new ArrayList<io.mateu.workflow.dtos.Variable>();
        if (variables != null) {
            variables.forEach((name, value) -> result.add(new io.mateu.workflow.dtos.Variable(name, text(value))));
        }
        return result;
    }

    static List<io.mateu.workflow.domain.aggregates.Variable> domainVariables(Map<String, ?> variables) {
        var result = new ArrayList<io.mateu.workflow.domain.aggregates.Variable>();
        if (variables != null) {
            variables.forEach((name, value) -> result.add(new io.mateu.workflow.domain.aggregates.Variable(name, text(value))));
        }
        return result;
    }

    /** Text that is a JSON object or array, parsed; anything else, as it is. */
    static Object parsed(String text) {
        var trimmed = text == null ? "" : text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (IOException notJson) {
                return text;
            }
        }
        return text;
    }

    static Map<String, Object> toMap(JsonNode node) {
        return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("not JSON: " + json, e);
        }
    }

    /**
     * Whether a stored (text) variable holds {@code expected}: equal as text, or — for a non-text
     * expectation, or text that is JSON — equal as JSON, so {@code 3}, {@code "3"} and {@code 3.0}
     * agree with how the engine wrote them only where they really are the same value.
     */
    static boolean matches(String actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == null && expected == null;
        }
        var expectedText = text(expected);
        if (Objects.equals(actual, expectedText)) {
            return true;
        }
        try {
            var a = JSON.readTree(actual);
            var e = JSON.readTree(expectedText);
            if (a != null && e != null && (a.isContainerNode() || a.isNumber() || a.isBoolean())) {
                if (a.isNumber() && e.isNumber()) {
                    return a.decimalValue().compareTo(e.decimalValue()) == 0;
                }
                return a.equals(e);
            }
        } catch (IOException notJson) {
            return false;
        }
        return false;
    }
}
