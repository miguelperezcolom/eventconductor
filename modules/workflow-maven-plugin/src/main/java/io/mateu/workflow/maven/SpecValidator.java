package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates EventConductor definition documents (already parsed to a {@link JsonNode},
 * so JSON and YAML are treated identically) against the canonical JSON schemas plus the
 * semantic checks a schema cannot express.
 *
 * <p>The three schemas are the exact files shipped by the engine modules
 * ({@code workflow-definition-schema.json}, {@code form-schema.json},
 * {@code rule-schema.json}), copied into this plugin at build time, so the plugin never
 * drifts from what the running engine enforces. The semantic layer mirrors the engine's
 * {@code checkInvariants()} / validator logic.
 */
public class SpecValidator {

    /** The kinds of definition this plugin can validate. */
    public enum Kind {
        WORKFLOW("schemas/workflow-definition-schema.json"),
        FORM("schemas/form-schema.json"),
        RULE("schemas/rule-schema.json");

        final String schemaResource;

        Kind(String schemaResource) {
            this.schemaResource = schemaResource;
        }
    }

    private final Schema workflowSchema;
    private final Schema formSchema;
    private final Schema ruleSchema;
    // parse-only engine; expressions are never evaluated during validation.
    private final JexlEngine jexl = new JexlBuilder().create();

    public SpecValidator() {
        this.workflowSchema = loadSchema(Kind.WORKFLOW.schemaResource);
        this.formSchema = loadSchema(Kind.FORM.schemaResource);
        this.ruleSchema = loadSchema(Kind.RULE.schemaResource);
    }

    private static Schema loadSchema(String resource) {
        try (InputStream stream = SpecValidator.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled schema not found on classpath: " + resource);
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load schema " + resource, e);
        }
    }

    /** Returns the list of violations for {@code document} of the given {@code kind}; empty means valid. */
    public List<String> validate(Kind kind, JsonNode document) {
        List<String> violations = new ArrayList<>();
        switch (kind) {
            case WORKFLOW -> {
                addSchemaViolations(workflowSchema, document, violations);
                addWorkflowSemantics(document, violations);
            }
            case FORM -> addSchemaViolations(formSchema, document, violations);
            case RULE -> {
                addSchemaViolations(ruleSchema, document, violations);
                addRuleSemantics(document, violations);
            }
        }
        return violations;
    }

    /**
     * Returns build-time warnings for {@code document} of the given {@code kind}; empty means
     * nothing worth flagging. Warnings point at legal-but-treacherous constructs and must never
     * fail the build — violations stay the exclusive business of {@link #validate}.
     */
    public List<String> warnings(Kind kind, JsonNode document) {
        List<String> warnings = new ArrayList<>();
        if (kind == Kind.WORKFLOW) {
            addWorkflowWarnings(document, warnings);
        }
        return warnings;
    }

    /**
     * A JOIN whose direct precondition step carries a guard ({@code preconditionExpression})
     * can never fire when that guard is false — implicit completion then cancels the JOIN and
     * everything after it, silently. Legal (fail-closed dataflow) but easy to trip over.
     */
    private void addWorkflowWarnings(JsonNode wf, List<String> warnings) {
        JsonNode steps = wf.get("steps");
        if (steps == null || !steps.isArray()) {
            return;
        }
        java.util.Map<String, JsonNode> stepsById = new java.util.HashMap<>();
        for (JsonNode step : steps) {
            String id = text(step, "id");
            if (id != null) {
                stepsById.put(id, step);
            }
        }
        for (JsonNode step : steps) {
            if (!"JOIN".equals(text(step, "type"))) {
                continue;
            }
            String id = text(step, "id");
            if (id == null) {
                continue;
            }
            for (String preconditionStepId : preconditions(step)) {
                JsonNode preconditionStep = stepsById.get(preconditionStepId);
                if (preconditionStep != null && isSet(text(preconditionStep, "preconditionExpression"))) {
                    warnings.add("JOIN '" + id + "' waits on guarded step '" + preconditionStepId
                            + "' — if its guard is false the join never fires and the flow beyond it is cancelled.");
                }
            }
        }
    }

    private void addSchemaViolations(Schema schema, JsonNode document, List<String> violations) {
        schema.validate(document.toString(), InputFormat.JSON).forEach(v -> violations.add(v.getInstanceLocation() + ": " + v.getMessage()));
    }

    // --- Workflow semantics: mirror WorkflowDefinition.checkInvariants() + cron validation. ---

    private void addWorkflowSemantics(JsonNode wf, List<String> violations) {
        JsonNode cron = wf.get("cronExpression");
        if (cron != null && cron.isTextual() && !cron.asText().isBlank()
                && !CronExpression.isValidExpression(cron.asText().trim())) {
            violations.add("'" + cron.asText() + "' is not a valid cron expression");
        }

        JsonNode steps = wf.get("steps");
        if (steps == null || !steps.isArray()) {
            return; // the schema already reports a missing/!array steps list
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode step : steps) {
            String id = text(step, "id");
            if (id == null) continue;
            if (!ids.add(id)) {
                violations.add("Duplicate step id '" + id + "'.");
            }
        }
        String workflowId = text(wf, "id");
        for (JsonNode step : steps) {
            String id = text(step, "id");
            if (id == null) continue;
            String type = text(step, "type");
            List<String> preconditions = preconditions(step);
            String compensation = text(step, "compensationStepId");
            if (preconditions.contains(id)) {
                violations.add("Step '" + id + "' cannot have itself as a precondition.");
            }
            if (id.equals(compensation)) {
                violations.add("Step '" + id + "' cannot have itself as a compensation step.");
            }
            for (String precondition : preconditions) {
                if (!ids.contains(precondition)) {
                    violations.add("Step '" + id + "' references unknown precondition step '" + precondition + "'.");
                }
            }
            if (isSet(compensation) && !ids.contains(compensation)) {
                violations.add("Step '" + id + "' references unknown compensation step '" + compensation + "'.");
            }
            if ("START".equals(type) && !preconditions.isEmpty()) {
                violations.add("START step '" + id + "' cannot have preconditions.");
            }
            if (!"START".equals(type) && !"WAIT_FOR_MESSAGE".equals(type) && preconditions.isEmpty()) {
                violations.add("Step '" + id + "' has no preconditions but is not a START or"
                        + " WAIT_FOR_MESSAGE — every flow must enter through one.");
            }
            if ("PROCESS".equals(type)) {
                String childWorkflowDefinitionId = text(step, "childWorkflowDefinitionId");
                if (!isSet(childWorkflowDefinitionId)) {
                    violations.add("Process step '" + id + "' must define a childWorkflowDefinitionId.");
                } else if (childWorkflowDefinitionId.equals(workflowId)) {
                    violations.add("Process step '" + id + "' cannot start this workflow itself as its child.");
                }
            }
            String precExpr = text(step, "preconditionExpression");
            if (isSet(precExpr)) {
                checkJexl(precExpr, "step '" + id + "' preconditionExpression", violations);
            }
            String corrExpr = text(step, "correlationExpression");
            if (isSet(corrExpr)) {
                checkJexl(corrExpr, "step '" + id + "' correlationExpression", violations);
            }
        }
        // Cycle detection: DFS (white/grey/black) over the multi-edge precondition graph —
        // revisiting a grey node means a cycle, and none of those steps could ever run.
        java.util.Map<String, List<String>> preconditionGraph = new java.util.HashMap<>();
        for (JsonNode step : steps) {
            String id = text(step, "id");
            if (id != null) {
                preconditionGraph.put(id, preconditions(step));
            }
        }
        Set<String> acyclic = new HashSet<>();
        for (String start : preconditionGraph.keySet()) {
            findPreconditionCycle(start, preconditionGraph, new java.util.LinkedHashSet<>(), acyclic, violations);
        }
    }

    /**
     * The effective precondition ids of a step: the plural {@code preconditionStepIds} when
     * non-empty, else the singular {@code preconditionStepId}, else none — mirroring
     * {@code Step.preconditions()} in the engine.
     */
    private static List<String> preconditions(JsonNode step) {
        JsonNode plural = step.get("preconditionStepIds");
        if (plural != null && plural.isArray() && !plural.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (JsonNode id : plural) {
                if (id.isTextual()) {
                    result.add(id.asText());
                }
            }
            return result;
        }
        String singular = text(step, "preconditionStepId");
        return isSet(singular) ? List.of(singular) : List.of();
    }

    /** DFS step for cycle detection: {@code path} is the grey set (current walk), {@code acyclic} the black set. */
    private static void findPreconditionCycle(String stepId, java.util.Map<String, List<String>> preconditions,
                                              java.util.LinkedHashSet<String> path, Set<String> acyclic,
                                              List<String> violations) {
        if (acyclic.contains(stepId)) return;
        if (!path.add(stepId)) {
            violations.add("Steps form a precondition cycle (" + String.join(" → ", path)
                    + " → " + stepId + "), so none of them could ever run.");
            return;
        }
        for (String preconditionStepId : preconditions.getOrDefault(stepId, List.of())) {
            findPreconditionCycle(preconditionStepId, preconditions, path, acyclic, violations);
        }
        path.remove(stepId);
        acyclic.add(stepId);
    }

    // --- Rule semantics: mirror RuleValidator (row arity + JEXL parseability). ---

    private void addRuleSemantics(JsonNode rule, List<String> violations) {
        // Rule type is serialized lowercase-hyphenated in the DSL (RuleType @JsonValue).
        String type = text(rule, "type");
        if ("expression".equals(type)) {
            String when = text(rule, "when");
            if (isSet(when)) {
                checkJexl(when, "when", violations);
            }
            JsonNode then = rule.get("then");
            if (then != null && then.isArray()) {
                for (JsonNode assignment : then) {
                    String name = text(assignment, "name");
                    checkJexl(text(assignment, "expression"), "then." + name, violations);
                }
            }
        } else if ("decision-table".equals(type)) {
            int inputs = size(rule.get("inputs"));
            int outputs = size(rule.get("outputs"));
            JsonNode rows = rule.get("rows");
            if (rows != null && rows.isArray()) {
                for (int i = 0; i < rows.size(); i++) {
                    JsonNode row = rows.get(i);
                    JsonNode when = row.get("when");
                    JsonNode then = row.get("then");
                    if (size(when) != inputs) {
                        violations.add("row " + i + ": 'when' must have " + inputs + " cells (one per input)");
                    }
                    if (size(then) != outputs) {
                        violations.add("row " + i + ": 'then' must have " + outputs + " cells (one per output)");
                    } else {
                        for (int c = 0; c < then.size(); c++) {
                            if (then.get(c).isTextual()) {
                                checkJexl(then.get(c).asText(), "row " + i + " then[" + c + "]", violations);
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkJexl(String expression, String where, List<String> violations) {
        if (expression == null) return;
        try {
            jexl.createExpression(expression);
        } catch (Exception e) {
            violations.add(where + ": invalid JEXL expression '" + expression + "' (" + e.getMessage() + ")");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static int size(JsonNode arrayNode) {
        return arrayNode != null && arrayNode.isArray() ? arrayNode.size() : 0;
    }
}
