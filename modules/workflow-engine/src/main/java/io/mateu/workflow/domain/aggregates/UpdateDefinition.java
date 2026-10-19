package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One named update a definition accepts: a validated write to a live process that answers with a
 * result — the engine's counterpart of a Temporal Workflow Update. Carried in the {@code .ec} file as an
 * entry of {@code updates}.
 *
 * <p>Applied atomically under the process lock, in this order: the process must be accepting
 * ({@code acceptedWhile} / {@code completes} step waiting), every {@code validate} guard must hold,
 * {@code set} is computed against the state before the update, {@code reply} against the state after it.
 * A refusal changes nothing. See {@code ApplyProcessUpdateUseCase}.
 *
 * <p>Every expression sees the process variables by name, {@code payload} (the update's JSON payload,
 * typed: an object is a map), {@code businessKey} and {@code process}.
 *
 * @param name          unique in the definition; the {@code {name}} of {@code POST …/updates/{name}}
 * @param description   what it is for, shown in the console and the MCP tools
 * @param accepts       optional JSON Schema (2020-12) the payload must satisfy — a 400 otherwise
 * @param acceptedWhile optional step ids: the update is accepted only while one of them is waiting
 *                      (its execution PENDING or RUNNING) — a 409 otherwise
 * @param validate      optional guards; the first that is not true refuses the update with its message (422)
 * @param set           variables written, name → JEXL expression; a value that is not text is stored as JSON
 * @param completes     optional: a {@code WAIT_FOR_MESSAGE} step the update resumes, so the flow moves on
 *                      (it must be waiting, as if listed in {@code acceptedWhile})
 * @param reply         optional JEXL expression whose value (as JSON) is the update's result
 * @param replyTemplate optional JSON template (as a REPLY's {@code replyTemplate}) for the result; one of
 *                      the two at most — neither answers {@code {}}
 * @param sensitive     payload fields never shown: the recorded copy of the payload carries a mask instead
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"name", "description", "accepts", "acceptedWhile", "validate", "set", "completes", "reply",
        "replyTemplate", "sensitive"})
public record UpdateDefinition(String name, String description, Map<String, Object> accepts,
                               List<String> acceptedWhile, List<Validation> validate, Map<String, String> set,
                               String completes, String reply, Object replyTemplate, List<String> sensitive) {

    public UpdateDefinition {
        name = name == null ? null : name.trim();
        description = blank(description) ? null : description;
        accepts = accepts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(accepts));
        acceptedWhile = acceptedWhile == null || acceptedWhile.isEmpty() ? null : List.copyOf(acceptedWhile);
        validate = validate == null || validate.isEmpty() ? null : List.copyOf(validate);
        // The author's order is the evaluation order of the reply's view, and the order shown.
        set = set == null || set.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(set));
        completes = blank(completes) ? null : completes.trim();
        reply = blank(reply) ? null : reply;
        sensitive = sensitive == null || sensitive.isEmpty() ? null : List.copyOf(sensitive);
    }

    /**
     * A guard: {@code expression} must evaluate to {@code true} for the update to be accepted; otherwise it
     * is refused with {@code message} (or a generic one naming the expression).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"expression", "message"})
    public record Validation(String expression, String message) {
    }

    /** The steps that must be waiting for the update to be accepted: {@code acceptedWhile}, plus {@code completes}. */
    public List<String> windowSteps() {
        var steps = new java.util.ArrayList<String>();
        if (acceptedWhile != null) {
            steps.addAll(acceptedWhile);
        }
        if (completes != null && !steps.contains(completes)) {
            steps.add(completes);
        }
        return steps;
    }

    /** Whether the payload field is declared sensitive. */
    public boolean isSensitive(String field) {
        return sensitive != null && field != null && sensitive.contains(field);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
