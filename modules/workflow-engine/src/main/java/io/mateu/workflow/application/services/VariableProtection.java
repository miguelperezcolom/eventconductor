package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.crypto.SensitiveNames;
import io.mateu.workflow.crypto.SensitivePayloads;
import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.crypto.VariableEnvelope;
import io.mateu.workflow.domain.aggregates.Variable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Sealing and opening at the engine's boundaries — the one place the repositories, the outbox, the
 * relay and the broker publishers go through, so the domain keeps working on plaintext and every write
 * that leaves the process is sealed the same way (see VARIABLE-ENCRYPTION-PLAN.md).
 *
 * <ul>
 *   <li>A list of variables (a process's or a step's row): the value of each sensitive one sealed, the
 *       names in clear.</li>
 *   <li>A blob that may embed values in shapes no policy can name (a sync reply, a forEach scope or
 *       collected output): sealed whole when the process has anything sensitive.</li>
 *   <li>An event (an outbox row, a broker message): every {@code {name, value}} in it sealed, by the
 *       policy of the process it names.</li>
 * </ul>
 *
 * Opening needs no policy: anything sealed is opened, anything else passes through.
 */
@Component
public class VariableProtection {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SensitiveVariablePolicies policies;

    public VariableProtection(SensitiveVariablePolicies policies) {
        this.policies = policies;
    }

    /** No policies, no encryption — for code built outside Spring. */
    public static VariableProtection none() {
        return new VariableProtection(SensitiveVariablePolicies.none());
    }

    public SensitiveVariablePolicies policies() {
        return policies;
    }

    private VariableEncryption encryption() {
        return policies.encryption();
    }

    /**
     * Whether these names call for sealing. Fails closed: a process whose definition declares sensitive
     * variables is never written in clear because the keys went missing (the startup check normally stops
     * that engine first; this holds when it could not run, e.g. the database was down at boot).
     */
    private boolean active(SensitiveNames names) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        if (!encryption().enabled()) {
            throw new io.mateu.workflow.crypto.EncryptionConfigurationException("Refusing to store sensitive variables "
                    + names.patterns() + " in clear: no encryption key is configured (workflow.encryption.keys.<id>).");
        }
        return true;
    }

    // ── variables ─────────────────────────────────────────────────────────────────

    /** The variables as their row stores them: JSON, sensitive values sealed. */
    public String variablesJson(List<Variable> variables, SensitiveNames names) {
        if (!active(names) || variables == null) {
            return toJson(variables);
        }
        var sealed = new ArrayList<Variable>(variables.size());
        for (var variable : variables) {
            sealed.add(variable != null && names.test(variable.name())
                    ? new Variable(variable.name(), encryption().seal(variable.value()))
                    : variable);
        }
        return toJson(sealed);
    }

    /** The variables of a row, opened. */
    public List<Variable> variablesFromJson(String json) {
        var variables = listFromJson(json, Variable.class);
        if (variables == null || !SensitivePayloads.containsSealed(json)) {
            return variables;
        }
        return open(variables);
    }

    /** The variables with every sealed value opened. */
    public List<Variable> open(List<Variable> variables) {
        if (variables == null) {
            return null;
        }
        var opened = new ArrayList<Variable>(variables.size());
        for (var variable : variables) {
            opened.add(variable != null && VariableEnvelope.isSealed(variable.value())
                    ? new Variable(variable.name(), encryption().open(variable.value()))
                    : variable);
        }
        return opened;
    }

    /** The {@code dtos.Variable}s with every sealed value opened. */
    public List<io.mateu.workflow.dtos.Variable> openDtos(List<io.mateu.workflow.dtos.Variable> variables) {
        if (variables == null || variables.stream().noneMatch(v -> v != null && VariableEnvelope.isSealed(v.value()))) {
            return variables;
        }
        return variables.stream()
                .map(v -> v != null && VariableEnvelope.isSealed(v.value())
                        ? new io.mateu.workflow.dtos.Variable(v.name(), encryption().open(v.value())) : v)
                .toList();
    }

    /** The {@code dtos.Variable}s with the sensitive values sealed. */
    public List<io.mateu.workflow.dtos.Variable> sealDtos(List<io.mateu.workflow.dtos.Variable> variables, SensitiveNames names) {
        if (!active(names) || variables == null) {
            return variables;
        }
        return variables.stream()
                .map(v -> v != null && names.test(v.name())
                        ? new io.mateu.workflow.dtos.Variable(v.name(), encryption().seal(v.value())) : v)
                .toList();
    }

    // ── blobs ─────────────────────────────────────────────────────────────────────

    /** A blob sealed whole when the process has anything sensitive; as it is otherwise. */
    public String sealBlob(String blob, SensitiveNames names) {
        return blob == null || !active(names) ? blob : encryption().seal(blob);
    }

    /** A blob opened when it was sealed. */
    public String openBlob(String blob) {
        return encryption().open(blob);
    }

    // ── events ────────────────────────────────────────────────────────────────────

    /** An outbox payload or a message body: the variables in it sealed, by the policy of the process it names. */
    public String sealPayload(String json, SensitiveNames names) {
        return active(names) ? SensitivePayloads.sealVariables(json, names, encryption()) : json;
    }

    /** A payload with every sealed value opened. */
    public String openPayload(String json) {
        return SensitivePayloads.open(json, encryption());
    }

    /**
     * An event about to leave the engine (a broker message): a copy with the variables it carries sealed,
     * by the policy of the process — or definition — it names; the event itself when nothing is sealed.
     */
    @SuppressWarnings("unchecked")
    public <E> E sealEvent(E event) {
        if (event == null || !encryption().enabled()) {
            return event;
        }
        var json = toJson(event);
        if (json == null || (!json.contains("\"value\"") && !json.contains("\"variableMapping\""))) {
            return event;
        }
        try {
            var tree = JSON.readTree(json);
            var names = policies.ofPayload(tree);
            if (names.isEmpty() || !SensitivePayloads.sealVariablesIn(tree, names, encryption())) {
                return event;
            }
            return (E) pojoFromJson(JSON.writeValueAsString(tree), event.getClass());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not seal the variables of a " + event.getClass().getSimpleName()
                    + ": " + e.getClass().getSimpleName());
        }
    }

    /**
     * An event as a log line may print it: its {@code toString()} when nothing is encrypted (no definition
     * can declare sensitive variables then); otherwise its type and JSON with every sensitive or sealed
     * value replaced by the mask — the engine never logs a sensitive value, in clear or sealed.
     */
    public String describe(Object event) {
        if (event == null || !encryption().enabled()) {
            return String.valueOf(event);
        }
        try {
            var tree = JSON.readTree(toJson(event));
            var names = policies.ofPayload(tree);
            mask(tree, names);
            return event.getClass().getSimpleName() + JSON.writeValueAsString(tree);
        } catch (Exception e) {
            return event.getClass().getSimpleName() + "[unprintable]";
        }
    }

    /** Masks, in place, the value of every sensitive variable and every sealed value of a tree. */
    public static void mask(com.fasterxml.jackson.databind.JsonNode node, java.util.function.Predicate<String> sensitive) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var name = object.get("name");
            var value = object.get("value");
            if (name != null && name.isTextual() && value != null && !value.isNull() && sensitive.test(name.asText())) {
                object.put("value", io.mateu.workflow.analysis.SensitiveVariableRules.MASK);
            }
            if (object.get("variableMapping") instanceof com.fasterxml.jackson.databind.node.ObjectNode mapping) {
                var mapped = new ArrayList<String>();
                mapping.fieldNames().forEachRemaining(mapped::add);
                mapped.stream().filter(sensitive).forEach(n -> mapping.put(n, io.mateu.workflow.analysis.SensitiveVariableRules.MASK));
            }
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (var field : fields) {
                var child = object.get(field);
                if (child.isTextual() && VariableEnvelope.isSealed(child.asText())) {
                    object.put(field, io.mateu.workflow.analysis.SensitiveVariableRules.MASK);
                } else if (child.isContainerNode()) {
                    mask(child, sensitive);
                }
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                var child = array.get(i);
                if (child.isTextual() && VariableEnvelope.isSealed(child.asText())) {
                    array.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(
                            io.mateu.workflow.analysis.SensitiveVariableRules.MASK));
                } else if (child.isContainerNode()) {
                    mask(child, sensitive);
                }
            }
        }
    }

    /** An event that came in (from the broker, a worker, the outbox): a copy with every sealed value opened. */
    @SuppressWarnings("unchecked")
    public <E> E openEvent(E event) {
        if (event == null) {
            return null;
        }
        var json = toJson(event);
        if (!SensitivePayloads.containsSealed(json)) {
            return event;
        }
        return (E) pojoFromJson(openPayload(json), event.getClass());
    }
}
