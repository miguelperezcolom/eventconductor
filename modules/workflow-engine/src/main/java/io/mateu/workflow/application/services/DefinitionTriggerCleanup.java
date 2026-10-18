package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps the event triggers created through the engine (UI, REST API, MCP) in line with the definition
 * they start, at the one place every definition is written and deleted: its repository.
 *
 * <ul>
 *   <li><b>A definition is deleted:</b> its engine-created triggers go with it, and so do the pauses
 *       of all of its triggers. Left behind they could not be reached any more ({@code
 *       EventTriggerService.delete} looks a trigger up through its definition), and re-creating a
 *       definition with the same id brought them back to life, paused or not.</li>
 *   <li><b>A definition is saved declaring a trigger:</b> an engine-created trigger with the same
 *       {@code <workflow>/<trigger>} is deleted — the definition's own is that trigger from now on, its
 *       pause (kept by key) carrying over. {@code EventTriggerImporter.reconcile} did this, but only
 *       after a file import; a definition saved any other way (through the repository, from code)
 *       left the engine-created copy hidden behind it, and deleting the definition's trigger later
 *       made the stale copy fire again.</li>
 * </ul>
 *
 * <p>Depends on the trigger stores only, not on the definitions, so the repositories can call it
 * without a cycle.
 */
@Slf4j
public class DefinitionTriggerCleanup {

    private final EventTriggerRepository eventTriggerRepository;
    private final TriggerPauseRepository triggerPauseRepository;

    public DefinitionTriggerCleanup(EventTriggerRepository eventTriggerRepository,
                                    TriggerPauseRepository triggerPauseRepository) {
        this.eventTriggerRepository = eventTriggerRepository;
        this.triggerPauseRepository = triggerPauseRepository;
    }

    public void definitionSaved(WorkflowDefinition definition) {
        if (definition == null || definition.triggers() == null || definition.triggers().isEmpty()
                || eventTriggerRepository == null) {
            return;
        }
        var declared = new HashSet<String>();
        definition.triggers().stream()
                .filter(trigger -> trigger != null && trigger.id() != null)
                .forEach(trigger -> declared.add(TriggerPause.keyOf(definition.id(), trigger.id())));
        if (declared.isEmpty()) {
            return;
        }
        var takenOver = eventTriggerRepository.findAll().stream()
                .filter(t -> EventFileTrigger.API_SOURCE.equals(t.source()))
                .map(EventFileTrigger::key)
                .filter(Objects::nonNull)
                .filter(declared::contains)
                .toList();
        if (!takenOver.isEmpty()) {
            eventTriggerRepository.deleteApiTriggers(takenOver);
            log.info("Event trigger(s) {} now declared by definition '{}': the copies created through the engine were replaced",
                    takenOver, definition.id());
        }
    }

    public void definitionsDeleted(Collection<String> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return;
        }
        var ids = Set.copyOf(workflowIds);
        if (eventTriggerRepository != null) {
            var owned = eventTriggerRepository.findAll().stream()
                    .filter(t -> EventFileTrigger.API_SOURCE.equals(t.source()) && ids.contains(t.workflowId()))
                    .map(EventFileTrigger::key)
                    .filter(Objects::nonNull)
                    .toList();
            if (!owned.isEmpty()) {
                eventTriggerRepository.deleteApiTriggers(owned);
                log.info("Event trigger(s) {} deleted with their definition", owned);
            }
        }
        if (triggerPauseRepository != null) {
            var pauses = triggerPauseRepository.findAll().keySet().stream()
                    .filter(key -> ids.stream().anyMatch(id -> key.startsWith(id + "/")))
                    .toList();
            pauses.forEach(triggerPauseRepository::delete);
            if (!pauses.isEmpty()) {
                log.info("Pause(s) of event trigger(s) {} deleted with their definition", pauses);
            }
        }
    }
}
