package io.mateu.workflow.domain.services;

import io.mateu.workflow.analysis.EventTriggerRules;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.EventTriggerOrigin;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every event trigger the engine knows, each with its id: the one place that turns definitions and
 * stored {@code events.yaml} entries into the triggers the inbound events are matched against, the UI
 * lists, and the REST API and MCP tools operate — so all of them mean the same trigger by the same
 * {@code <workflow>/<trigger>}. Pure.
 *
 * <p>Order, which is also precedence when two declarations carry one id in one workflow: the
 * definition's own {@code triggers} (their ids as {@link WorkflowDefinition#identifiedTriggers()} gives
 * them — never changed by what a file declares), then the {@code events.yaml} entries naming that
 * workflow (by source, then entry), then the triggers created through the engine (by id). A file entry
 * without an id gets one derived from its type, as a definition's trigger does
 * ({@code com.acme.booking.created}, {@code -2} on a repeat within the workflow). A file entry whose
 * declared id the workflow already has is listed with {@link Entry#duplicateOf()} set and never fires:
 * the first declaration owns the id. A trigger created through the engine whose id a file now declares
 * is not listed at all: the file's entry has replaced it (the import that brought it deletes the stored
 * one; until then it is ignored).
 *
 * <p>Every definition is listed, whatever its status — whether a trigger starts anything also depends on
 * its workflow accepting instances and on its pause, which is the receiver's and the UI's business. A
 * file entry naming a workflow the engine no longer has is dropped: there is nothing it could start.
 */
public final class EventTriggerCatalog {

    private EventTriggerCatalog() {
    }

    /**
     * One trigger of one workflow.
     *
     * @param definition  the workflow it starts
     * @param trigger     the trigger, its id always set (declared or derived)
     * @param origin      where it is declared
     * @param source      the definition id, the import source key of its {@code events.yaml}, or
     *                    {@link EventFileTrigger#API_SOURCE}
     * @param position    its index in the definition's {@code triggers} or in its source
     * @param duplicateOf where the owner of its id is declared, when another declaration took the id
     *                    first — such an entry never fires; null otherwise
     */
    public record Entry(WorkflowDefinition definition, EventTrigger trigger, EventTriggerOrigin origin, String source,
                        int position, String duplicateOf) {

        /** The key its pause is kept under: {@code <workflow>/<trigger>}. */
        public String key() {
            return TriggerPause.keyOf(definition.id(), trigger.id());
        }

        /** Whether this declaration is the one that starts processes (not a duplicate id). */
        public boolean fires() {
            return duplicateOf == null;
        }

        /** "Definition", "events.yaml (git)", "UI/API"… */
        public String sourceLabel() {
            return origin.label(source);
        }

        /** Whether it can be edited and deleted through the engine: only what was created there. */
        public boolean editable() {
            return origin == EventTriggerOrigin.API;
        }

        /** Exactly where it is declared. */
        public String declaredIn() {
            return switch (origin) {
                case DEFINITION -> "definition " + definition.id() + " triggers[" + position + "]";
                case EVENTS_FILE -> sourceLabel() + " " + source + " entry " + position;
                case API -> "UI/API";
            };
        }
    }

    /** The triggers of {@code definitions} and of the {@code stored} file entries, in precedence order. */
    public static List<Entry> of(Collection<WorkflowDefinition> definitions, Collection<EventFileTrigger> stored) {
        var entries = new ArrayList<Entry>();
        var sorted = definitions.stream()
                .filter(d -> d.id() != null)
                .sorted(Comparator.comparing(WorkflowDefinition::id))
                .toList();
        var filesByWorkflow = stored.stream()
                .filter(t -> t.workflowId() != null && t.trigger() != null && !EventFileTrigger.API_SOURCE.equals(t.source()))
                .sorted(Comparator.comparing(EventFileTrigger::source).thenComparingInt(EventFileTrigger::index))
                .collect(Collectors.groupingBy(EventFileTrigger::workflowId));
        var apiByWorkflow = stored.stream()
                .filter(t -> t.workflowId() != null && t.trigger() != null && t.trigger().id() != null
                        && EventFileTrigger.API_SOURCE.equals(t.source()))
                .sorted(Comparator.comparing(t -> t.trigger().id()))
                .collect(Collectors.groupingBy(EventFileTrigger::workflowId));
        for (var definition : sorted) {
            var owners = new HashMap<String, Entry>();
            var own = definition.identifiedTriggers();
            for (int i = 0; i < own.size(); i++) {
                var entry = new Entry(definition, own.get(i), EventTriggerOrigin.DEFINITION, definition.id(), i, null);
                owners.putIfAbsent(entry.trigger().id(), entry);
                entries.add(entry);
            }
            var taken = new HashSet<>(owners.keySet());
            var files = filesByWorkflow.getOrDefault(definition.id(), List.of());
            // Declared ids are claimed first, so a derived one never takes an id a later entry declares.
            for (var file : files) {
                if (file.trigger().id() != null) {
                    taken.add(file.trigger().id());
                }
            }
            for (var file : files) {
                var trigger = file.trigger();
                String id;
                String duplicateOf = null;
                if (trigger.id() != null) {
                    id = trigger.id();
                    var owner = owners.get(id);
                    if (owner != null) {
                        duplicateOf = owner.declaredIn();
                    }
                } else {
                    id = uniqueDerived(trigger.type(), taken);
                    taken.add(id);
                }
                var entry = new Entry(definition, trigger.withId(id), EventTriggerOrigin.EVENTS_FILE, file.source(),
                        file.index(), duplicateOf);
                if (duplicateOf == null) {
                    owners.put(id, entry);
                }
                entries.add(entry);
            }
            for (var api : apiByWorkflow.getOrDefault(definition.id(), List.of())) {
                if (owners.containsKey(api.trigger().id())) {
                    continue;   // a file declares this id now: its entry is the trigger
                }
                var entry = new Entry(definition, api.trigger(), EventTriggerOrigin.API, EventFileTrigger.API_SOURCE,
                        api.index(), null);
                owners.put(api.trigger().id(), entry);
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * The keys ({@code <workflow>/<trigger>}) files declare (definitions and {@code events.yaml}, firing
     * or duplicate): a trigger created through the engine with one of them has been taken over.
     */
    public static Set<String> fileOwnedKeys(List<Entry> catalog) {
        return catalog.stream().filter(e -> e.origin() != EventTriggerOrigin.API).map(Entry::key)
                .collect(Collectors.toSet());
    }

    /** The entry owning {@code <workflowId>/<triggerId>}, if any (never a duplicate). */
    public static java.util.Optional<Entry> owner(List<Entry> catalog, String workflowId, String triggerId) {
        return catalog.stream()
                .filter(e -> e.fires() && e.definition().id().equals(workflowId) && e.trigger().id().equals(triggerId))
                .findFirst();
    }

    /** The keys ({@code <workflow>/<trigger>}) of every trigger in the catalog, duplicates included. */
    public static Set<String> keys(List<Entry> catalog) {
        return catalog.stream().map(Entry::key).collect(Collectors.toSet());
    }

    /**
     * The {@code businessKey} templates that triggers of more than one workflow share, each with those
     * workflows (sorted). A business key is unique across workflows — creation is ignored when any
     * process already has it — so whichever of them starts first for a rendered key takes it, and the
     * others start nothing for that event. Usually a mistake: put the workflow in the template
     * ({@code "booking-onboarding-${data.bookingId}"}).
     */
    public static Map<String, java.util.SortedSet<String>> sharedBusinessKeys(List<Entry> catalog) {
        var byTemplate = new java.util.TreeMap<String, java.util.SortedSet<String>>();
        catalog.stream()
                .filter(e -> e.fires() && e.trigger().businessKey() != null && !e.trigger().businessKey().isBlank())
                .forEach(e -> byTemplate.computeIfAbsent(e.trigger().businessKey().strip(), k -> new java.util.TreeSet<>())
                        .add(e.definition().id()));
        byTemplate.values().removeIf(workflows -> workflows.size() < 2);
        return byTemplate;
    }

    /** The derived id, with {@code -2}, {@code -3}… while {@code taken} holds it. */
    private static String uniqueDerived(String type, Set<String> taken) {
        var base = EventTriggerRules.derivedId(type);
        var candidate = base;
        int n = 1;
        while (taken.contains(candidate)) {
            n++;
            candidate = base + "-" + n;
        }
        return candidate;
    }

    /** The catalog grouped by workflow id, in catalog order. */
    public static Map<String, List<Entry>> byWorkflow(List<Entry> catalog) {
        return catalog.stream().collect(Collectors.groupingBy(e -> e.definition().id(),
                java.util.LinkedHashMap::new, Collectors.toList()));
    }
}
