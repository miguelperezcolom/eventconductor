package io.mateu.workflow.worker.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.mateu.workflow.dtos.Variable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the engine's {@code List<Variable>} (name/value string pairs) to and from a task's typed
 * input/output records with Jackson. A value is read as JSON when it parses as one (so an
 * {@code integer}, {@code boolean}, {@code object} or {@code array} attribute round-trips), and as
 * a plain string otherwise (so a {@code string} or a {@code date} stays itself). A binding error is
 * surfaced to the caller as a clear message, which the dispatcher turns into a failed reply.
 */
final class VariableBinding {

    private final ObjectMapper mapper;

    VariableBinding(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Build the typed input from the task's variables. Returns null for a no-input handler. */
    <I> I toInput(List<Variable> variables, Class<I> inputType) {
        if (inputType == null || inputType == Void.class) {
            return null;
        }
        ObjectNode node = mapper.createObjectNode();
        if (variables != null) {
            for (var variable : variables) {
                node.set(variable.name(), asNode(variable.value()));
            }
        }
        try {
            return mapper.convertValue(node, inputType);
        } catch (IllegalArgumentException e) {
            throw new BindingException("could not read the process variables as "
                    + inputType.getSimpleName() + ": " + rootMessage(e));
        }
    }

    /** Flatten a task's typed output back into variables. */
    List<Variable> toVariables(Object output) {
        if (output == null) {
            return List.of();
        }
        Map<String, Object> map;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = mapper.convertValue(output, LinkedHashMap.class);
            map = converted;
        } catch (IllegalArgumentException e) {
            throw new BindingException("could not read the task output: " + rootMessage(e));
        }
        var result = new ArrayList<Variable>(map.size());
        map.forEach((name, value) -> result.add(new Variable(name, stringify(value))));
        return result;
    }

    private JsonNode asNode(String value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            // A value that parses as JSON is used as such (number/boolean/object/array); anything
            // else — a bare word, an ISO date — is a plain string.
            return mapper.readTree(value);
        } catch (Exception e) {
            return TextNode.valueOf(value);
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /** A binding failure — turned into a failed reply with a clear reason by the dispatcher. */
    static final class BindingException extends RuntimeException {
        BindingException(String message) {
            super(message);
        }
    }
}
