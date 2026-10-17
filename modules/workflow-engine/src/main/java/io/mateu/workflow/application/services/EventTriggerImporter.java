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
 * <p>An entry that is malformed, breaks the trigger rules, or names a workflow the engine does not know
 * is reported and left out; the rest of the file still applies, and so does an id declared twice for
 * one workflow — the second entry is left out. A file that cannot be read at all leaves the source's
 * previous triggers in place rather than silently clearing them.
 *
 * <p><b>Files own what they declare.</b> An import replaces its own source only. After every import the
 * pauses are brought in line: a pause whose trigger no longer exists — removed from its file or its
 * definition — is deleted, and gone means gone: declaring it again later starts it unpaused.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventTriggerImporter {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> ENTRY_FIELDS =
            Set.of("id", "destination", "type", "workflow", "filter", "businessKey", "variables");
    private static final String ALLOWED = "id, destination, type, workflow, filter, businessKey, variables";

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final EventTriggerRepository eventTriggerRepository;
    final TriggerPauseRepository triggerPauseRepository;

    /** One events file of a source, as read off disk or the classpath. */
    public record EventFile(String name, byte[] content) {
    }

    /** One valid entry of an events file. */
    public record Entry(String workflow, EventTrigger trigger) {
    }

    /** A file's valid entries and what was wrong with the rest; {@code unreadable} when nothing could be read. */
    public record Parsed(List<Entry> entries, List<String> errors, boolean unreadable) {
    }

    /** What an import of one source did. */
    public record Result(List<EventFileTrigger> triggers, List<String> errors) {
    }

    /**
     * Replaces the triggers of {@code source} with the entries of {@code files} (empty clears it).
     * Entries naming an unknown workflow are reported and skipped.
     */
    public Result importSource(String source, List<EventFile> files) {
        var errors = new ArrayList<String>();
        var triggers = new ArrayList<EventFileTrigger>();
        var ids = new HashSet<String>();
        for (var file : files) {
            var parsed = parse(file.name(), file.content());
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
                if (workflowDefinitionRepository.findById(entry.workflow()).isEmpty()) {
                    errors.add(file.name() + ": trigger on '" + entry.trigger().destination() + "' for type '"
                            + entry.trigger().type() + "' targets unknown workflow '" + entry.workflow() + "' — skipped");
                    continue;
                }
                triggers.add(new EventFileTrigger(source, triggers.size(), entry.workflow(), entry.trigger()));
            }
        }
        errors.forEach(e -> log.error("{}", e));
        eventTriggerRepository.replace(source, triggers);
        if (!triggers.isEmpty()) {
            log.info("Loaded {} event trigger(s) from {}", triggers.size(), source);
        }
        reconcile();
        return new Result(List.copyOf(triggers), errors);
    }

    /**
     * Deletes the pauses of triggers that no longer exist, wherever they were declared. Runs after every
     * import, which is when that changes. Pauses are read before the catalog, so a trigger declared and
     * paused meanwhile (on another pod) is in both reads or in neither, never taken for an orphan.
     */
    public void reconcile() {
        if (triggerPauseRepository == null) {
            return;
        }
        var paused = triggerPauseRepository.findAll().keySet();
        if (paused.isEmpty()) {
            return;
        }
        var known = EventTriggerCatalog.keys(
                EventTriggerCatalog.of(workflowDefinitionRepository.findAll(), eventTriggerRepository.findAll()));
        var orphans = paused.stream().filter(key -> !known.contains(key)).toList();
        if (!orphans.isEmpty()) {
            orphans.forEach(triggerPauseRepository::delete);
            log.info("Pause(s) of event trigger(s) {} removed: the triggers no longer exist", orphans);
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
            if (entryErrors.isEmpty()) {
                entries.add(new Entry(workflow.trim(), new EventTrigger(id, destination, type, filter, businessKey, variables)));
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
