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
 * workflow (by source, then entry). A file entry without an id gets one derived from its type, as a
 * definition's trigger does ({@code com.acme.booking.created}, {@code -2} on a repeat within the
 * workflow). A file entry whose declared id the workflow already has is listed with
 * {@link Entry#duplicateOf()} set and never fires: the first declaration owns the id.
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
     * @param source      the definition id, or the import source key of its {@code events.yaml}
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

        /** "Definition", "events.yaml (git)"… */
        public String sourceLabel() {
            return origin.label(source);
        }

        /** Exactly where it is declared. */
        public String declaredIn() {
            return switch (origin) {
                case DEFINITION -> "definition " + definition.id() + " triggers[" + position + "]";
                case EVENTS_FILE -> sourceLabel() + " " + source + " entry " + position;
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
                .filter(t -> t.workflowId() != null && t.trigger() != null)
                .sorted(Comparator.comparing(EventFileTrigger::source).thenComparingInt(EventFileTrigger::index))
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
        }
        return entries;
    }

    /** The keys ({@code <workflow>/<trigger>}) of every trigger in the catalog, duplicates included. */
    public static Set<String> keys(List<Entry> catalog) {
        return catalog.stream().map(Entry::key).collect(Collectors.toSet());
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
