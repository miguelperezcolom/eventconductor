package io.mateu.workflow.application.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.analysis.EventTriggerRules;
import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.services.EventTriggerCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code events.yaml} files and makes their entries the event triggers of the source they came
 * from — the counterpart of {@link CronScheduleImporter} for {@code cron.yaml}.
 *
 * <p>An events file is YAML (or JSON), either a bare list of entries or an object whose only key is
 * {@code triggers} holding that list. An entry is a definition's trigger plus the workflow it starts:
 *
 * <pre>
 * - id: on-customer-changed          # optional, the trigger's name within the workflow
 *   destination: customers-cdc       # → workflow.events.destinations.customers-cdc.topic
 *   type: "*"                        # the CloudEvents type, '*' wildcards allowed
 *   workflow: sync-customer          # the definition a matching event starts
 *   filter: "data.op == 'u'"         # optional JEXL over the event
 *   businessKey: "customer-${data.after.id}"   # optional template
 *   variables:                       # optional templates; default: data's top-level members
 *     customerId: "${data.after.id}"
 * </pre>
 *
 * <p>An entry that is malformed or breaks the trigger rules is reported and left out; the rest of the
 * file still applies, and so does an id declared twice for one workflow — the second entry is left out.
 * An entry naming a workflow the engine does not know <em>yet</em> is kept, with a warning: the sources
 * are imported one after another (the classpath first, then directories and git clones), so an
 * {@code events.yaml} may name a workflow another source brings later. Until that workflow exists the
 * entry matches nothing and is not listed ({@link EventTriggerCatalog} drops it); once it is imported,
 * the trigger applies. A file that cannot be read at all leaves the source's previous triggers in place
 * rather than silently clearing them.
 *
 * <p><b>Files own what they declare.</b> An import replaces its own source and never the triggers
 * created through the engine (UI, REST API, MCP) — except that one whose {@code <workflow>/<trigger>}
 * a file now declares (in an {@code events.yaml}, or in a definition's {@code triggers}) is deleted:
 * the file's entry is that trigger from now on, and its pause, kept by key, carries over. After every
 * import the pauses are brought in line too: a pause whose trigger no longer exists — removed from its
 * file or its definition — is deleted, and gone means gone: declaring it again later starts it
 * unpaused. See {@link #reconcile()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventTriggerImporter {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> ENTRY_FIELDS =
            Set.of("id", "destination", "type", "workflow", "filter", "businessKey", "variables", "startPolicy");
    private static final String ALLOWED = "id, destination, type, workflow, filter, businessKey, variables, startPolicy";

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final EventTriggerRepository eventTriggerRepository;
    final TriggerPauseRepository triggerPauseRepository;

    /** One events file of a source, as read off disk or the classpath. */
    /** {@code content} is null for a file that is there but could not be read: the source is left unchanged. */
    public record EventFile(String name, byte[] content) {
    }

    /** One valid entry of an events file. */
    public record Entry(String workflow, EventTrigger trigger) {
    }

    /** A file's valid entries and what was wrong with the rest; {@code unreadable} when nothing could be read. */
    public record Parsed(List<Entry> entries, List<String> errors, boolean unreadable) {
    }

    /**
     * What an import of one source did.
     *
     * @param errors   entries left out, or a file that could not be read
     * @param warnings entries kept that cannot apply yet: they name a workflow the engine does not know
     */
    public record Result(List<EventFileTrigger> triggers, List<String> errors, List<String> warnings) {

        public Result(List<EventFileTrigger> triggers, List<String> errors) {
            this(triggers, errors, List.of());
        }
    }

    /**
     * Replaces the triggers of {@code source} with the entries of {@code files} (empty clears it).
     * Entries naming a workflow not known yet are kept and reported as warnings.
     */
    public Result importSource(String source, List<EventFile> files) {
        if (EventFileTrigger.API_SOURCE.equals(source)) {
            throw new IllegalArgumentException("'" + source + "' holds the triggers created through the engine; no file is imported into it");
        }
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var triggers = new ArrayList<EventFileTrigger>();
        var ids = new HashSet<String>();
        for (var file : files) {
            // A file whose bytes could not be read (an IO error, over the size cap) is as unknown as one
            // that does not parse: it is not an empty file, so nothing the source had is dropped for it.
            var parsed = file.content() == null
                    ? new Parsed(List.of(), List.of(file.name() + ": the file could not be read"), true)
                    : parse(file.name(), file.content());
            errors.addAll(parsed.errors());
            if (parsed.unreadable()) {
                errors.forEach(e -> log.error("{}", e));
                log.error("Event triggers from {} left unchanged: {} could not be read", source, file.name());
                reconcile();
                return new Result(List.of(), errors);
            }
            for (var entry : parsed.entries()) {
                var id = entry.trigger().id();
                if (id != null && !ids.add(entry.workflow() + "/" + id)) {
                    errors.add(file.name() + ": trigger id '" + id + "' is declared more than once for workflow '"
                            + entry.workflow() + "' in " + source + " — the later entry is skipped");
                    continue;
                }
                var workflow = workflowDefinitionRepository.findById(entry.workflow());
                var sensitive = workflow.map(io.mateu.workflow.domain.aggregates.WorkflowDefinition::sensitiveMatcher).orElse(null);
                if (sensitive != null) {
                    var refused = io.mateu.workflow.domain.aggregates.WorkflowDefinition.sensitiveTriggerKeyViolations(
                            entry.trigger(), sensitive, file.name() + " trigger '" + entry.trigger().type() + "' for '"
                                    + entry.workflow() + "'");
                    if (!refused.isEmpty()) {
                        errors.addAll(refused);
                        continue;
                    }
                }
                if (workflow.isEmpty()) {
                    // Kept: another source (a directory, a git clone) may import it after this one.
                    warnings.add(file.name() + ": trigger on '" + entry.trigger().destination() + "' for type '"
                            + entry.trigger().type() + "': workflow '" + entry.workflow()
                            + "' not known yet — the trigger applies once it is imported");
                }
                triggers.add(new EventFileTrigger(source, triggers.size(), entry.workflow(), entry.trigger()));
            }
        }
        errors.forEach(e -> log.error("{}", e));
        warnings.forEach(w -> log.warn("{}", w));
        eventTriggerRepository.replace(source, triggers);
        if (!triggers.isEmpty()) {
            log.info("Loaded {} event trigger(s) from {}", triggers.size(), source);
        }
        reconcile();
        return new Result(List.copyOf(triggers), errors, List.copyOf(warnings));
    }

    /**
     * Brings the engine-created triggers and the pauses in line with what the files now declare. Runs
     * after every import, which is when that changes.
     *
     * <ul>
     *   <li>A trigger created through the engine whose {@code <workflow>/<trigger>} a file declares is
     *       deleted: the file's entry replaces it. Until this runs it is already ignored
     *       ({@code EventTriggerCatalog}), so the two never both fire.</li>
     *   <li>A pause whose trigger no longer exists is deleted — the trigger was removed from its file,
     *       and gone means gone: declaring it again later starts it unpaused.</li>
     * </ul>
     *
     * <p>Pauses are read before the catalog, so a trigger declared and paused meanwhile (on another pod)
     * is in both reads or in neither, never taken for an orphan.
     */
    public void reconcile() {
        var paused = triggerPauseRepository == null ? Set.<String>of() : triggerPauseRepository.findAll().keySet();
        var stored = eventTriggerRepository.findAll();
        var catalog = EventTriggerCatalog.of(workflowDefinitionRepository.findAll(), stored);
        warnOfSharedBusinessKeys(catalog);
        var fileOwned = EventTriggerCatalog.fileOwnedKeys(catalog);
        var takenOver = stored.stream()
                .filter(t -> EventFileTrigger.API_SOURCE.equals(t.source()) && fileOwned.contains(t.key()))
                .map(EventFileTrigger::key)
                .toList();
        if (!takenOver.isEmpty()) {
            eventTriggerRepository.deleteApiTriggers(takenOver);
            log.info("Event trigger(s) {} now declared in files: the copies created through the engine were replaced", takenOver);
        }
        if (paused.isEmpty()) {
            return;
        }
        var known = EventTriggerCatalog.keys(catalog);
        var orphans = paused.stream().filter(key -> !known.contains(key)).toList();
        if (!orphans.isEmpty()) {
            orphans.forEach(triggerPauseRepository::delete);
            log.info("Pause(s) of event trigger(s) {} removed: the triggers no longer exist", orphans);
        }
    }

    /**
     * Logs a WARN for every {@code businessKey} template that triggers of different workflows share: a
     * business key is unique across workflows, so for one rendered key only the first of them starts a
     * process and the others start nothing.
     */
    static void warnOfSharedBusinessKeys(List<EventTriggerCatalog.Entry> catalog) {
        EventTriggerCatalog.sharedBusinessKeys(catalog).forEach((template, workflows) -> log.warn(
                "Event triggers of workflows {} share the businessKey template '{}': a business key is unique across"
                        + " workflows, so for an event rendering one key only the first of them starts a process."
                        + " Put the workflow in the template (e.g. '<workflow>-${data.id}').", workflows, template));
    }

    /** An entry's {@code startPolicy}, validated by the shared rules; null when absent or invalid (with errors). */
    private static io.mateu.workflow.domain.aggregates.StartPolicy startPolicy(JsonNode node, String where, List<String> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        Object raw;
        try {
            raw = YAML.treeToValue(node, Object.class);
        } catch (Exception e) {
            errors.add(where + ": startPolicy could not be read (" + e.getMessage() + ")");
            return null;
        }
        var violations = io.mateu.workflow.analysis.FlowControlRules.startPolicyViolations(raw, where + " startPolicy");
        if (!violations.isEmpty()) {
            errors.addAll(violations);
            return null;
        }
        try {
            return YAML.treeToValue(node, io.mateu.workflow.domain.aggregates.StartPolicy.class);
        } catch (Exception e) {
            errors.add(where + ": startPolicy could not be read (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Parses one events file into its valid entries and the errors of the others. Pure. */
    public static Parsed parse(String fileName, byte[] content) {
        JsonNode root;
        try {
            // YAML reads JSON too.
            root = content == null || content.length == 0 ? null : YAML.readTree(content);
        } catch (Exception e) {
            return new Parsed(List.of(), List.of(fileName + ": could not be parsed (" + e.getMessage() + ")"), true);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return new Parsed(List.of(), List.of(), false);   // an empty file declares no triggers
        }
        JsonNode list = root;
        if (root.isObject()) {
            if (root.size() != 1 || !root.has("triggers")) {
                return unreadable(fileName);
            }
            list = root.get("triggers");
            if (list.isNull()) {
                return new Parsed(List.of(), List.of(), false);
            }
        }
        if (!list.isArray()) {
            return unreadable(fileName);
        }
        var entries = new ArrayList<Entry>();
        var errors = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            var where = fileName + " entry " + i;
            var node = list.get(i);
            var entryErrors = new ArrayList<String>();
            if (!node.isObject()) {
                errors.add(where + ": expected an object with destination, type and workflow");
                continue;
            }
            node.fieldNames().forEachRemaining(field -> {
                if (!ENTRY_FIELDS.contains(field)) {
                    entryErrors.add(where + ": unknown field '" + field + "' (allowed: " + ALLOWED + ")");
                }
            });
            var id = text(node, "id", where, entryErrors);
            var destination = text(node, "destination", where, entryErrors);
            var type = text(node, "type", where, entryErrors);
            var workflow = text(node, "workflow", where, entryErrors);
            var filter = text(node, "filter", where, entryErrors);
            var businessKey = text(node, "businessKey", where, entryErrors);
            if (workflow == null || workflow.isBlank()) {
                entryErrors.add(where + ": a trigger needs the id of the workflow it starts ('workflow')");
            }
            entryErrors.addAll(EventTriggerRules.triggerViolations(id, destination, type, where));
            var variables = variables(node.get("variables"), where, entryErrors);
            entryErrors.addAll(io.mateu.workflow.domain.aggregates.WorkflowDefinition.eventTemplateProblems(
                    filter, businessKey, variables, where));
            var startPolicy = startPolicy(node.get("startPolicy"), where, entryErrors);
            if (entryErrors.isEmpty()) {
                entries.add(new Entry(workflow.trim(), new EventTrigger(id, destination, type, filter, businessKey, variables,
                        startPolicy)));
            } else {
                errors.addAll(entryErrors);
            }
        }
        return new Parsed(entries, errors, false);
    }

    private static Parsed unreadable(String fileName) {
        return new Parsed(List.of(), List.of(fileName
                + ": expected a list of triggers ({id?, destination, type, workflow, filter?, businessKey?, variables?})"
                + " or an object with a single 'triggers' key"), true);
    }

    private static String text(JsonNode node, String field, String where, List<String> errors) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(where + ": '" + field + "' must be a string");
            return null;
        }
        return value.asText();
    }

    /** The variable templates as written: strings, numbers, booleans, nested objects and lists. */
    private static Map<String, Object> variables(JsonNode node, String where, List<String> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(where + ": 'variables' must be a map of name: template");
            return null;
        }
        return YAML.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }
}
