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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        RULE("schemas/rule-schema.json"),
        TASK("schemas/task-contract-schema.json"),
        /** A {@code cron.yaml} / {@code cron.yml} schedule file next to the workflow definitions. */
        CRON("schemas/cron-schema.json"),
        /** An {@code events.yaml} / {@code events.yml} event-trigger file next to the workflow definitions. */
        EVENTS("schemas/events-schema.json");

        final String schemaResource;

        Kind(String schemaResource) {
            this.schemaResource = schemaResource;
        }
    }

    private final Schema workflowSchema;
    private final Schema formSchema;
    private final Schema ruleSchema;
    private final Schema taskSchema;
    private final Schema cronSchema;
    private final Schema eventsSchema;
    // parse-only engine; expressions are never evaluated during validation.
    private final JexlEngine jexl = new JexlBuilder().create();

    public SpecValidator() {
        this.workflowSchema = loadSchema(Kind.WORKFLOW.schemaResource);
        this.formSchema = loadSchema(Kind.FORM.schemaResource);
        this.ruleSchema = loadSchema(Kind.RULE.schemaResource);
        this.taskSchema = loadSchema(Kind.TASK.schemaResource);
        this.cronSchema = loadSchema(Kind.CRON.schemaResource);
        this.eventsSchema = loadSchema(Kind.EVENTS.schemaResource);
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
            case TASK -> addSchemaViolations(taskSchema, document, violations);
            case CRON -> {
                addSchemaViolations(cronSchema, document, violations);
                addCronSemantics(document, violations);
            }
            case EVENTS -> {
                addSchemaViolations(eventsSchema, document, violations);
                addEventSemantics(document, violations);
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
        warnings.addAll(replyAnalysis(wf).warnings());
        for (JsonNode step : steps) {
            String id = text(step, "id");
            if (id != null) {
                warnings.addAll(io.mateu.workflow.analysis.AwaitReplyRules.misplacedWaitFieldWarnings(id,
                        text(step, "type"), isSet(text(step, "messageName")),
                        isSet(text(step, "correlationExpression"))));
            }
        }
        warnings.addAll(loopAnalysis(wf).warnings());
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

    // --- Schedules: the engine's own ScheduleRules (definition-analysis), not a copy of them. ---

    /**
     * The entries of a cron file — a bare list, or the list under a single {@code schedules} key —
     * or an empty list when the document is neither (the schema reports that).
     */
    public static List<JsonNode> cronEntries(JsonNode document) {
        JsonNode list = document != null && document.isObject() ? document.get("schedules") : document;
        List<JsonNode> entries = new ArrayList<>();
        if (list != null && list.isArray()) {
            list.forEach(entries::add);
        }
        return entries;
    }

    /** The entries of an events file: a bare list, or the list under {@code triggers}. */
    public static List<JsonNode> eventEntries(JsonNode document) {
        JsonNode list = document != null && document.isObject() ? document.get("triggers") : document;
        List<JsonNode> entries = new ArrayList<>();
        if (list != null && list.isArray()) {
            list.forEach(entries::add);
        }
        return entries;
    }

    /** The engine's trigger rules per entry, and an id declared twice for one workflow. */
    private void addEventSemantics(JsonNode document, List<String> violations) {
        List<JsonNode> entries = eventEntries(document);
        java.util.Map<String, List<String>> idsByWorkflow = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            if (text(entry, "destination") == null || text(entry, "type") == null) {
                continue; // the schema already reports a missing or non-string destination or type
            }
            violations.addAll(io.mateu.workflow.analysis.EventTriggerRules.triggerViolations(
                    text(entry, "id"), text(entry, "destination"), text(entry, "type"), "entry " + i));
            String workflow = text(entry, "workflow");
            if (workflow != null) {
                idsByWorkflow.computeIfAbsent(workflow.trim(), w -> new ArrayList<>()).add(text(entry, "id"));
            }
        }
        idsByWorkflow.forEach((workflow, ids) -> violations.addAll(
                io.mateu.workflow.analysis.EventTriggerRules.duplicateIdViolations(ids, "workflow '" + workflow + "'")));
    }

    private void addCronSemantics(JsonNode document, List<String> violations) {
        List<JsonNode> entries = cronEntries(document);
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            String cron = text(entry, "cron");
            if (cron == null) {
                continue; // the schema already reports a missing or non-string cron
            }
            violations.addAll(io.mateu.workflow.analysis.ScheduleRules.violations(
                    text(entry, "id"), cron, text(entry, "zone"), "entry " + i));
        }
        violations.addAll(io.mateu.workflow.analysis.ScheduleRules.duplicateIdViolations(
                entries.stream().map(entry -> text(entry, "id")).toList(), null));
    }

    // --- Workflow semantics: mirror WorkflowDefinition.checkInvariants() + cron validation. ---

    private void addWorkflowSemantics(JsonNode wf, List<String> violations) {
        JsonNode tags = wf.get("tags");
        if (tags != null && tags.isArray()) {
            List<String> values = new ArrayList<>();
            tags.forEach(t -> values.add(t.isTextual() ? t.asText() : t.toString()));
            violations.addAll(io.mateu.workflow.analysis.TagRules.violations(values, "tags"));
        }
        JsonNode retention = wf.get("retention");
        if (retention != null && retention.isTextual()) {
            violations.addAll(io.mateu.workflow.analysis.RetentionRules.violations(retention.asText(), null, "retention"));
        } else if (retention != null && retention.isObject()) {
            Map<String, String> byStatus = new LinkedHashMap<>();
            retention.fields().forEachRemaining(e -> byStatus.put(e.getKey(),
                    e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
            violations.addAll(io.mateu.workflow.analysis.RetentionRules.violations(null, byStatus, "retention"));
        }
        JsonNode cron = wf.get("cronExpression");
        if (cron != null && cron.isTextual() && !cron.asText().isBlank()
                && !CronExpression.isValidExpression(cron.asText().trim())) {
            violations.add("'" + cron.asText() + "' is not a valid cron expression");
        }
        JsonNode schedules = wf.get("schedules");
        if (schedules != null && schedules.isArray()) {
            for (int i = 0; i < schedules.size(); i++) {
                String scheduleCron = text(schedules.get(i), "cron");
                if (scheduleCron != null) {
                    violations.addAll(io.mateu.workflow.analysis.ScheduleRules.violations(
                            text(schedules.get(i), "id"), scheduleCron, text(schedules.get(i), "zone"),
                            "schedules[" + i + "]"));
                }
            }
            var scheduleIds = new ArrayList<String>();
            schedules.forEach(schedule -> scheduleIds.add(text(schedule, "id")));
            violations.addAll(io.mateu.workflow.analysis.ScheduleRules.duplicateIdViolations(scheduleIds, "schedules"));
        }
        // Event triggers: the engine's own EventTriggerRules, then their filter and templates.
        JsonNode triggers = wf.get("triggers");
        if (triggers != null && triggers.isArray()) {
            var triggerIds = new ArrayList<String>();
            for (int i = 0; i < triggers.size(); i++) {
                JsonNode trigger = triggers.get(i);
                String where = "triggers[" + i + "]";
                triggerIds.add(text(trigger, "id"));
                violations.addAll(io.mateu.workflow.analysis.EventTriggerRules.triggerViolations(
                        text(trigger, "id"), text(trigger, "destination"), text(trigger, "type"), where));
                checkEventReading(trigger, "businessKey", where, violations);
            }
            violations.addAll(io.mateu.workflow.analysis.EventTriggerRules.duplicateIdViolations(triggerIds, "triggers"));
        }

        JsonNode steps = wf.get("steps");
        if (steps == null || !steps.isArray()) {
            return; // the schema already reports a missing/!array steps list
        }
        Set<String> ids = new HashSet<>();
        // A compensation is declared on the step it undoes and started by the rollback pipeline,
        // so it needs no way in of its own — which the reachability check below has to know.
        Set<String> compensationTargets = new HashSet<>();
        for (JsonNode step : steps) {
            String id = text(step, "id");
            if (id == null) continue;
            if (!ids.add(id)) {
                violations.add("Duplicate step id '" + id + "'.");
            }
            String compensationOfThisStep = text(step, "compensationStepId");
            if (isSet(compensationOfThisStep)) {
                compensationTargets.add(compensationOfThisStep);
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
            if (!"START".equals(type) && !"WAIT_FOR_MESSAGE".equals(type) && preconditions.isEmpty()
                    && !compensationTargets.contains(id)) {
                violations.add("Step '" + id + "' has no preconditions and is not a START, a"
                        + " WAIT_FOR_MESSAGE or another step's compensation — nothing would ever"
                        + " start it.");
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
            // The conditions on the links are checked too — they are the preferred place to write
            // one, and an unparseable guard there fails closed at runtime just as silently.
            JsonNode links = step.get("preconditions");
            if (links != null && links.isArray()) {
                for (JsonNode link : links) {
                    String linkExpr = text(link, "expression");
                    if (isSet(linkExpr)) {
                        checkJexl(linkExpr, "step '" + id + "' precondition on '"
                                + text(link, "stepId") + "'", violations);
                    }
                }
            }
            String corrExpr = text(step, "correlationExpression");
            if (isSet(corrExpr)) {
                checkJexl(corrExpr, "step '" + id + "' correlationExpression", violations);
            }
            String replyExpr = text(step, "replyExpression");
            if (isSet(replyExpr)) {
                checkJexl(replyExpr, "step '" + id + "' replyExpression", violations);
            }
            checkTemplate(step.get("replyTemplate"), "step '" + id + "' replyTemplate", violations);
            if ("HTTP_CALL".equals(type)) {
                JsonNode http = step.get("http");
                @SuppressWarnings("unchecked")
                var httpMap = http == null || !http.isObject() ? null
                        : (java.util.Map<String, Object>) TEMPLATE_READER.convertValue(http, java.util.Map.class);
                violations.addAll(io.mateu.workflow.analysis.HttpCallRules.problems(httpMap, "HTTP_CALL step '" + id + "'"));
                if (httpMap != null) {
                    for (var field : List.of("url", "path", "query", "headers", "body", "bodyTemplate")) {
                        var value = http.get(field);
                        if (value != null && !value.toString().contains("${secret:")) {
                            checkTemplate(value, "step '" + id + "' http." + field, violations);
                        }
                    }
                    JsonNode output = http.get("output");
                    if (output != null && output.isObject()) {
                        output.properties().forEach(entry -> checkJexl(entry.getValue().asText(),
                                "step '" + id + "' http.output." + entry.getKey(), violations));
                    }
                }
            }
            if ("LLM_CALL".equals(type)) {
                JsonNode llm = step.get("llm");
                @SuppressWarnings("unchecked")
                var llmMap = llm == null || !llm.isObject() ? null
                        : (java.util.Map<String, Object>) TEMPLATE_READER.convertValue(llm, java.util.Map.class);
                violations.addAll(io.mateu.workflow.analysis.LlmCallRules.problems(llmMap, "LLM_CALL step '" + id + "'"));
                if (llmMap != null) {
                    for (var field : List.of("system", "prompt")) {
                        var value = llm.get(field);
                        if (value != null && !value.toString().contains("${secret:")) {
                            checkTemplate(value, "step '" + id + "' llm." + field, violations);
                        }
                    }
                    JsonNode output = llm.get("output");
                    if (output != null && output.isObject()) {
                        output.properties().forEach(entry -> checkJexl(entry.getValue().asText(),
                                "step '" + id + "' llm.output." + entry.getKey(), violations));
                    }
                }
            }
            checkMoment(step.get("until"), "TIMER step '" + id + "' until", true, violations);
            checkMoment(step.get("deadline"), "Step '" + id + "' deadline", false, violations);
            JsonNode onEvent = step.get("onEvent");
            if (onEvent != null && onEvent.isObject()) {
                String where = "Step '" + id + "' onEvent";
                violations.addAll(io.mateu.workflow.analysis.EventTriggerRules.onEventPlacementViolations(id, type, true));
                violations.addAll(io.mateu.workflow.analysis.EventTriggerRules.sourceViolations(
                        text(onEvent, "destination"), text(onEvent, "type"), where));
                checkEventReading(onEvent, "correlationKey", where, violations);
            }
            // awaitReply: the engine's own rules (definition-analysis), then its expression and templates.
            JsonNode awaitReply = step.get("awaitReply");
            boolean hasAwaitReply = awaitReply != null && awaitReply.isObject();
            JsonNode replyEvent = hasAwaitReply ? awaitReply.get("onEvent") : null;
            boolean hasReplyEvent = replyEvent != null && replyEvent.isObject();
            violations.addAll(io.mateu.workflow.analysis.AwaitReplyRules.violations(id, type, hasAwaitReply,
                    hasAwaitReply ? text(awaitReply, "messageName") : null,
                    hasAwaitReply ? text(awaitReply, "correlationExpression") : null,
                    hasReplyEvent,
                    hasReplyEvent ? text(replyEvent, "destination") : null,
                    hasReplyEvent ? text(replyEvent, "type") : null));
            if (hasAwaitReply) {
                String replyCorrelation = text(awaitReply, "correlationExpression");
                if (isSet(replyCorrelation)) {
                    checkJexl(replyCorrelation, "step '" + id + "' awaitReply.correlationExpression", violations);
                }
                if (hasReplyEvent) {
                    checkEventReading(replyEvent, "correlationKey", "Step '" + id + "' awaitReply.onEvent", violations);
                }
            }
            // forEach / repeat / loopBack: their shape is LoopRules' (below); their expressions here.
            JsonNode forEach = step.get("forEach");
            if (forEach != null && forEach.isObject()) {
                checkJexl(text(forEach, "items"), "step '" + id + "' forEach.items", violations);
                checkJexl(text(forEach, "outputElement"), "step '" + id + "' forEach.outputElement", violations);
                String completion = text(forEach, "completion");
                if (isSet(completion) && !"all".equalsIgnoreCase(completion.trim()) && !"any".equalsIgnoreCase(completion.trim())) {
                    checkJexl(completion, "step '" + id + "' forEach.completion", violations);
                }
            }
            JsonNode repeat = step.get("repeat");
            if (repeat != null && repeat.isObject()) {
                checkJexl(text(repeat, "until"), "step '" + id + "' repeat.until", violations);
            }
            JsonNode loopBack = step.get("loopBack");
            if (loopBack != null && loopBack.isObject() && isSet(text(loopBack, "while"))) {
                checkJexl(text(loopBack, "while"), "step '" + id + "' loopBack.while", violations);
            }
            JsonNode event = step.get("event");
            if (event != null && event.isObject()) {
                checkTemplate(event.get("payload"), "step '" + id + "' event.payload", violations);
                checkTemplate(event.get("payloadTemplate"), "step '" + id + "' event.payloadTemplate", violations);
                checkTemplate(event.get("key"), "step '" + id + "' event.key", violations);
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
        int violationsBeforeCycles = violations.size();
        for (String start : preconditionGraph.keySet()) {
            findPreconditionCycle(start, preconditionGraph, new java.util.LinkedHashSet<>(), acyclic, violations);
        }
        // The REPLY rules run the engine's own analysis (definition-analysis), which assumes an
        // acyclic graph — so only once the cycle check found nothing.
        if (violations.size() == violationsBeforeCycles) {
            violations.addAll(loopAnalysis(wf).errors());
            violations.addAll(replyAnalysis(wf).errors());
        }
    }

    /**
     * A payload template: its {@code ${…}} must be well formed and every expression must parse — the
     * same syntax (definition-analysis) the engine renders with.
     */
    /** A moment ({@code until} / {@code deadline}): its rules, then the templates in its date and zone. */
    private void checkMoment(JsonNode moment, String where, boolean allowIfPast, List<String> violations) {
        if (moment == null || moment.isNull()) {
            return;
        }
        Object generic = TEMPLATE_READER.convertValue(moment, Object.class);
        var problems = io.mateu.workflow.analysis.MomentRules.problems(generic, where, allowIfPast);
        violations.addAll(problems);
        if (problems.isEmpty()) {
            checkTemplate(moment.isTextual() ? moment : moment.get("date"), where + " date", violations);
            checkTemplate(moment.get("zone"), where + " zone", violations);
        }
    }

    /** A trigger's or an onEvent's filter (JEXL) and its text template and variable templates. */
    private void checkEventReading(JsonNode source, String textTemplateField, String where, List<String> violations) {
        String filter = text(source, "filter");
        if (isSet(filter)) {
            checkJexl(filter, where + " filter", violations);
        }
        checkTemplate(source.get(textTemplateField), where + " " + textTemplateField, violations);
        checkTemplate(source.get("variables"), where + " variables", violations);
    }

    private void checkTemplate(JsonNode template, String where, List<String> violations) {
        if (template == null || template.isNull()) {
            return;
        }
        List<java.util.Map.Entry<String, String>> expressions;
        try {
            expressions = io.mateu.workflow.analysis.TemplateSyntax.expressions(
                    TEMPLATE_READER.convertValue(template, Object.class), where);
        } catch (io.mateu.workflow.analysis.TemplateSyntax.TemplateSyntaxException e) {
            violations.add(where + ": " + e.getMessage());
            return;
        }
        for (var expression : expressions) {
            checkJexl(expression.getValue(), expression.getKey(), violations);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper TEMPLATE_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** The shared loop and multi-instance rules (definition-analysis {@code LoopRules}) over this document's steps. */
    private static io.mateu.workflow.analysis.LoopRules.Report loopAnalysis(JsonNode wf) {
        JsonNode steps = wf.get("steps");
        List<io.mateu.workflow.analysis.LoopRules.Node> nodes = new ArrayList<>();
        if (steps != null && steps.isArray()) {
            for (JsonNode step : steps) {
                String id = text(step, "id");
                if (id == null) continue;
                nodes.add(new io.mateu.workflow.analysis.LoopRules.Node(id, text(step, "type"), preconditions(step),
                        text(step, "compensationStepId"), block(step, "forEach"), block(step, "repeat"),
                        block(step, "loopBack")));
            }
        }
        return io.mateu.workflow.analysis.LoopRules.analyze(nodes);
    }

    private static java.util.Map<?, ?> block(JsonNode step, String field) {
        JsonNode value = step.get(field);
        return value == null || !value.isObject() ? null : TEMPLATE_READER.convertValue(value, java.util.Map.class);
    }

    /** The shared REPLY path analysis over this document's steps. */
    private static io.mateu.workflow.analysis.ReplyPathAnalyzer.Report replyAnalysis(JsonNode wf) {
        JsonNode steps = wf.get("steps");
        List<io.mateu.workflow.analysis.ReplyPathAnalyzer.Node> nodes = new ArrayList<>();
        if (steps != null && steps.isArray()) {
            for (JsonNode step : steps) {
                String id = text(step, "id");
                if (id == null) continue;
                nodes.add(new io.mateu.workflow.analysis.ReplyPathAnalyzer.Node(id, text(step, "type"),
                        preconditions(step), text(step, "onTimeoutStepId"), text(step, "compensationStepId")));
            }
        }
        JsonNode sync = wf.get("syncInvocation");
        boolean syncEnabled = sync != null && sync.path("enabled").asBoolean(false);
        return io.mateu.workflow.analysis.ReplyPathAnalyzer.analyze(nodes, syncEnabled);
    }

    /**
     * The effective precondition ids of a step: the plural {@code preconditionStepIds} when
     * non-empty, else the singular {@code preconditionStepId}, else none — mirroring
     * {@code Step.preconditionIds()} in the engine.
     */
    /**
     * The steps this one waits for, whichever of the three spellings the definition uses — the
     * same precedence the engine applies in {@code Step.resolvedPreconditions()}: the {@code
     * preconditions} links first, then the plural ids, then the singular. Reading only the older
     * two would make every link-declared step look like it waits for nothing, which the roots rule
     * reports as a step nothing would ever start.
     */
    private static List<String> preconditions(JsonNode step) {
        JsonNode links = step.get("preconditions");
        if (links != null && links.isArray() && !links.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (JsonNode link : links) {
                String stepId = text(link, "stepId");
                if (isSet(stepId)) {
                    result.add(stepId);
                }
            }
            return result;
        }
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
