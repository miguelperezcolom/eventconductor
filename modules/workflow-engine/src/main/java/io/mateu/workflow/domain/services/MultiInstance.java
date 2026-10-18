package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.ForEach;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.Variable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.application.services.JEXLEvaluator.evaluate;

/**
 * The pure parts of multi-instance ({@code forEach}): what the items are, what one iteration sees,
 * what it contributes to {@code output}, and whether the container is done.
 *
 * <p>Process variables are strings in this engine. Two places read them as structure instead,
 * because a list is what a forEach is about: {@code items}, where a variable holding a JSON array or
 * object is that array or object, and an iteration's scope, whose item is handed to HTTP/LLM
 * templates typed (so {@code ${room.number}} works) while workers receive it as JSON text.
 */
public final class MultiInstance {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    /** Thrown when {@code items} cannot be turned into a list; the container fails with the message. */
    public static final class ItemsException extends RuntimeException {
        public ItemsException(String message) {
            super(message);
        }
    }

    /**
     * Evaluates {@code items} against the process. A collection or array is the list; a string
     * holding a JSON array is parsed; null is the empty list. Anything else is an error.
     */
    public static List<Object> items(ForEach forEach, Process process) {
        var context = new HashMap<String, Object>();
        process.getVariables().forEach(variable -> context.put(variable.name(), structured(variable.value())));
        context.put("businessKey", process.getBusinessKey());
        Object value;
        try {
            value = evaluate(forEach.items(), context).value();
        } catch (RuntimeException e) {
            throw new ItemsException("forEach items '" + forEach.items() + "' could not be evaluated: " + e.getMessage());
        }
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value.getClass().isArray()) {
            var list = new ArrayList<Object>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                list.add(java.lang.reflect.Array.get(value, i));
            }
            return list;
        }
        if (value instanceof String text && text.trim().startsWith("[")) {
            try {
                return JSON.readValue(text, new TypeReference<List<Object>>() {
                });
            } catch (Exception e) {
                throw new ItemsException("forEach items '" + forEach.items() + "' gave a string that is not a JSON array.");
            }
        }
        throw new ItemsException("forEach items '" + forEach.items() + "' gave "
                + value.getClass().getSimpleName() + ", not a list.");
    }

    /** What one iteration sees laid over the process variables: the item and its index, as strings. */
    public static Map<String, String> scopeFor(ForEach forEach, Object item, int index) {
        var scope = new LinkedHashMap<String, String>();
        scope.put(forEach.itemVariable(), asVariableValue(item));
        scope.put(forEach.indexVariable(), String.valueOf(index));
        return scope;
    }

    /** A value as a process variable holds it: text as is, JSON for anything structured. */
    public static String asVariableValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** A variable value read as structure when it is a JSON object or array, as itself otherwise. */
    public static Object structured(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (Exception e) {
                return value;
            }
        }
        return value;
    }

    /** A scope with its JSON objects and arrays parsed — what templates see. */
    public static Map<String, Object> typed(Map<String, String> scope) {
        var typed = new LinkedHashMap<String, Object>();
        if (scope != null) {
            scope.forEach((name, value) -> typed.put(name, structured(value)));
        }
        return typed;
    }

    /** The process variables with a scope laid over them. */
    public static List<Variable> overlay(List<Variable> variables, Map<String, String> scope) {
        if (scope == null || scope.isEmpty()) {
            return variables;
        }
        var result = new ArrayList<Variable>();
        for (var variable : variables) {
            if (!scope.containsKey(variable.name())) {
                result.add(variable);
            }
        }
        scope.forEach((name, value) -> result.add(new Variable(name, value)));
        return result;
    }

    /**
     * One iteration's element of {@code output}: {@code outputElement} evaluated over what it reported
     * (and its scope), or, without one, an object of everything it reported. Null for an iteration
     * that did not complete.
     */
    public static Object element(ForEach forEach, StepExecution iteration) {
        if (iteration == null || iteration.getStatus() != io.mateu.workflow.domain.aggregates.StepExecutionStatus.COMPLETED) {
            return null;
        }
        var reported = new LinkedHashMap<String, Object>();
        iteration.output().forEach((name, value) -> reported.put(name, structured(value)));
        if (forEach.outputElement() == null || forEach.outputElement().isBlank()) {
            return reported;
        }
        var context = new HashMap<String, Object>(typed(iteration.scope()));
        context.putAll(reported);
        try {
            return evaluate(forEach.outputElement(), context).value();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The {@code output} array, one element per iteration in index order, as JSON. */
    public static String output(ForEach forEach, List<StepExecution> iterations, int total) {
        var elements = new ArrayList<Object>();
        for (int i = 0; i < total; i++) {
            elements.add(null);
        }
        for (var iteration : iterations) {
            var index = iteration.getItemIndex();
            if (index != null && index >= 0 && index < total) {
                elements.set(index, element(forEach, iteration));
            }
        }
        try {
            return JSON.writeValueAsString(elements);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Whether a {@code completion} condition holds: evaluated over the process variables, the
     * {@code output} gathered so far (under its own name, when declared), and the counts
     * {@code completedIterations}, {@code totalIterations} and {@code activeIterations}.
     */
    public static boolean conditionHolds(ForEach forEach, Process process, List<StepExecution> iterations,
                                         int total, int completed, int active) {
        var context = new HashMap<String, Object>();
        process.getVariables().forEach(variable -> context.put(variable.name(), variable.value()));
        if (forEach.hasOutput()) {
            context.put(forEach.output(), structured(output(forEach, iterations, total)));
        }
        context.put("completedIterations", completed);
        context.put("totalIterations", total);
        context.put("activeIterations", active);
        context.put("businessKey", process.getBusinessKey());
        try {
            var value = evaluate(forEach.completionCondition(), context).value();
            return Truthiness.isTruthy(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Reads a JSON object of strings (a scope or an output), empty when absent or unreadable. */
    public static Map<String, String> stringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            var raw = JSON.readValue(json, MAP);
            var result = new LinkedHashMap<String, String>();
            raw.forEach((name, value) -> result.put(name, value == null ? null : asVariableValue(value)));
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("not representable as JSON", e);
        }
    }

    private MultiInstance() {
    }
}
