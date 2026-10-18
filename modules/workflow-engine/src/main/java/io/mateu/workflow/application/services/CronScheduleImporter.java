package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.analysis.ScheduleRules;
import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.services.ScheduleCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code cron.yaml} files and makes their entries the schedules of the source they came from.
 *
 * <p>A cron file is YAML (or JSON), either a bare list of entries or an object whose only key is
 * {@code schedules} holding that list:
 *
 * <pre>
 * - id: daily-report-emea      # optional, the schedule's name (one schedule per id)
 *   cron: "0 0 9 * * MON-FRI"
 *   workflow: daily-report
 *   zone: Europe/Madrid          # optional, default workflow.time.zone
 *   variables:                   # optional, the started process's initial variables
 *     region: EMEA
 * </pre>
 *
 * <p>An entry that is malformed, has an invalid cron or zone, or names a workflow the engine does not
 * know is reported and left out; the rest of the file still applies, and so does an id declared
 * twice — the second entry is left out. A file that cannot be read at all leaves the source's previous
 * schedules in place rather than silently clearing them.
 *
 * <p><b>Files own what they declare.</b> An import replaces its own source and never the schedules
 * created through the engine (UI, REST API, MCP) — except that one whose id a file now declares (in a
 * {@code cron.yaml}, or in a definition's {@code schedules}) is deleted: the file's entry, with the
 * file's cron, zone, variables and workflow, is that schedule from now on, and its pause, kept by id,
 * carries over. An entry that disappears from its file is deleted with its pause — including one that
 * started life in the UI. See {@link #reconcile()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CronScheduleImporter {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> ENTRY_FIELDS = Set.of("id", "cron", "workflow", "zone", "variables");

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final CronScheduleRepository cronScheduleRepository;
    final SchedulePauseRepository schedulePauseRepository;

    /** One cron file of a source, as read off disk or the classpath. */
    /** {@code content} is null for a file that is there but could not be read: the source is left unchanged. */
    public record CronFile(String name, byte[] content) {
    }

    /** One valid entry of a cron file. */
    public record Entry(String workflow, CronSchedule schedule) {
    }

    /** A file's valid entries and what was wrong with the rest; {@code unreadable} when nothing could be read. */
    public record Parsed(List<Entry> entries, List<String> errors, boolean unreadable) {
    }

    /** What an import of one source did. */
    public record Result(List<CronFileSchedule> schedules, List<String> errors) {
    }

    /**
     * Replaces the schedules of {@code source} with the entries of {@code files} (empty clears it).
     * Entries naming an unknown workflow are reported and skipped.
     */
    public Result importSource(String source, List<CronFile> files) {
        if (Schedule.API_SOURCE.equals(source)) {
            throw new IllegalArgumentException("'" + source + "' holds the schedules created through the engine; no file is imported into it");
        }
        var errors = new ArrayList<String>();
        var schedules = new ArrayList<CronFileSchedule>();
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
                log.error("Schedules from {} left unchanged: {} could not be read", source, file.name());
                // The definitions of this import did change: what they now declare still takes over.
                reconcile();
                return new Result(List.of(), errors);
            }
            for (var entry : parsed.entries()) {
                var id = entry.schedule().id();
                if (id != null && !ids.add(id)) {
                    errors.add(file.name() + ": schedule id '" + id + "' is declared more than once in " + source
                            + " — the later entry is skipped");
                    continue;
                }
                if (workflowDefinitionRepository.findById(entry.workflow()).isEmpty()) {
                    errors.add(file.name() + ": schedule '" + entry.schedule().cron() + "' targets unknown workflow '"
                            + entry.workflow() + "' — skipped");
                    continue;
                }
                schedules.add(new CronFileSchedule(source, schedules.size(), entry.workflow(), entry.schedule()));
            }
        }
        errors.forEach(e -> log.error("{}", e));
        cronScheduleRepository.replace(source, schedules);
        if (!schedules.isEmpty()) {
            log.info("Loaded {} schedule(s) from {}", schedules.size(), source);
        }
        reconcile();
        return new Result(List.copyOf(schedules), errors);
    }

    /**
     * Brings the engine-created schedules and the pauses in line with what the files now declare.
     * Runs after every import, which is when that changes.
     *
     * <ul>
     *   <li>A schedule created through the engine whose id a file declares is deleted: the file's entry
     *       replaces it. Until this runs it is already ignored ({@code ScheduleCatalog}), so the two
     *       never both fire; with the same id, the same occurrence has the same business key anyway.</li>
     *   <li>A pause whose schedule no longer exists is deleted — the schedule was removed from its file,
     *       and gone means gone: declaring it again later starts it unpaused.</li>
     * </ul>
     */
    public void reconcile() {
        // Pauses read before the schedules: a schedule created and paused meanwhile (on another pod) is
        // then either in both reads or in neither, never taken for an orphan.
        var pausedIds = schedulePauseRepository == null ? java.util.Set.<String>of()
                : schedulePauseRepository.findAll().keySet();
        var catalog = ScheduleCatalog.of(workflowDefinitionRepository.findAll(), cronScheduleRepository.findAll());
        var takenOver = new HashSet<String>();
        var fileOwned = ScheduleCatalog.fileOwnedIds(catalog);
        for (var stored : cronScheduleRepository.findAll()) {
            if (Schedule.API_SOURCE.equals(stored.source()) && fileOwned.contains(stored.schedule().id())) {
                takenOver.add(stored.schedule().id());
            }
        }
        if (!takenOver.isEmpty()) {
            cronScheduleRepository.deleteApiSchedules(takenOver);
            log.info("Schedule(s) {} now declared in files: the copies created through the engine were replaced", takenOver);
        }
        if (schedulePauseRepository == null) {
            return;
        }
        var known = catalog.stream().map(Schedule::id).collect(java.util.stream.Collectors.toSet());
        var orphans = pausedIds.stream().filter(id -> !known.contains(id)).toList();
        if (!orphans.isEmpty()) {
            schedulePauseRepository.deleteAll(orphans);
            log.info("Pause(s) of schedule(s) {} removed: the schedules no longer exist", orphans);
        }
    }

    /** Parses one cron file into its valid entries and the errors of the others. Pure. */
    public static Parsed parse(String fileName, byte[] content) {
        JsonNode root;
        try {
            // YAML reads JSON too.
            root = content == null || content.length == 0 ? null : YAML.readTree(content);
        } catch (Exception e) {
            return new Parsed(List.of(), List.of(fileName + ": could not be parsed (" + e.getMessage() + ")"), true);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return new Parsed(List.of(), List.of(), false);   // an empty file declares no schedules
        }
        JsonNode list = root;
        if (root.isObject()) {
            if (root.size() != 1 || !root.has("schedules")) {
                return unreadable(fileName);
            }
            list = root.get("schedules");
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
                errors.add(where + ": expected an object with cron and workflow");
                continue;
            }
            node.fieldNames().forEachRemaining(field -> {
                if (!ENTRY_FIELDS.contains(field)) {
                    entryErrors.add(where + ": unknown field '" + field + "' (allowed: id, cron, workflow, zone, variables)");
                }
            });
            var cron = text(node, "cron", where, entryErrors);
            var workflow = text(node, "workflow", where, entryErrors);
            var zone = text(node, "zone", where, entryErrors);
            var id = text(node, "id", where, entryErrors);
            if (workflow == null || workflow.isBlank()) {
                entryErrors.add(where + ": a schedule needs the id of the workflow it starts ('workflow')");
            }
            entryErrors.addAll(ScheduleRules.violations(id, cron, zone, where));
            var variables = variables(node.get("variables"), where, entryErrors);
            if (entryErrors.isEmpty()) {
                entries.add(new Entry(workflow.trim(), new CronSchedule(id, cron, zone, variables)));
            } else {
                errors.addAll(entryErrors);
            }
        }
        return new Parsed(entries, errors, false);
    }

    private static Parsed unreadable(String fileName) {
        return new Parsed(List.of(), List.of(fileName
                + ": expected a list of schedules ({id?, cron, workflow, zone?, variables?}) or an object with a single 'schedules' key"),
                true);
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

    private static LinkedHashMap<String, String> variables(JsonNode node, String where, List<String> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(where + ": 'variables' must be a map of name: value");
            return null;
        }
        var variables = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(field -> {
            var value = field.getValue();
            if (value.isValueNode() && !value.isNull()) {
                variables.put(field.getKey(), value.asText());
            } else {
                errors.add(where + ": variable '" + field.getKey() + "' must be a string, number or boolean");
            }
        });
        return variables;
    }
}
