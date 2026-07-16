package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.Variable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns String/String process {@link Variable}s into typed facts: numbers,
 * booleans and JSON payloads are coerced, and dotted names ({@code order.total})
 * become nested maps so JEXL expressions can navigate them.
 */
public class FactCoercer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> toFacts(List<Variable> variables) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach(variable -> put(facts, variable.name(), coerce(variable.value())));
        }
        return facts;
    }

    public Object coerce(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private void put(Map<String, Object> facts, String name, Object value) {
        if (name == null || name.isBlank()) {
            return;
        }
        var parts = name.split("\\.");
        var target = facts;
        for (int i = 0; i < parts.length - 1; i++) {
            var next = target.computeIfAbsent(parts[i], key -> new LinkedHashMap<String, Object>());
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                target.put(parts[i], next);
            }
            target = (Map<String, Object>) next;
        }
        target.put(parts[parts.length - 1], value);
    }
}
