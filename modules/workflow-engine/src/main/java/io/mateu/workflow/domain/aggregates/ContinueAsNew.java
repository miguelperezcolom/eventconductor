package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A CONTINUE_AS_NEW step's block: which definition version the next run starts from, and which
 * variables it carries. Carried in the file as {@code continueAsNew: { version, variables }}; absent
 * means the same version with every variable. See FLOW-CONTROL-PLAN.md §4.1.
 *
 * @param version   {@code same} (the definition snapshot this process runs; the default) or
 *                  {@code latest} (the definition as the engine has it now)
 * @param variables absent: every variable; a list of names: those; a map: name → template over the process
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "variables"})
public record ContinueAsNew(String version, Object variables) {

    /** Whether the next run starts from the latest version of the definition. */
    @JsonIgnore
    public boolean latest() {
        return "latest".equals(version);
    }

    /** The block as the file writes it, for the shared rules. */
    public java.util.Map<String, Object> asMap() {
        var map = new java.util.LinkedHashMap<String, Object>();
        if (version != null) map.put("version", version);
        if (variables != null) map.put("variables", variables);
        return map;
    }
}
