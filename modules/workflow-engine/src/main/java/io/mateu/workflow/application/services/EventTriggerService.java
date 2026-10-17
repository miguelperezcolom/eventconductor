package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.EventTriggerOrigin;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.EventTriggerCatalog;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The event triggers as the admin UI, the REST API and the MCP tools see them: consult them, pause and
 * resume them, and read what the received events did. Triggers are declared in definitions
 * ({@code triggers}) and in {@code events.yaml} files next to them, and changed there; this only
 * operates them. {@link EventTriggerCatalog} is what makes the two sources one list.
 */
@Service
@RequiredArgsConstructor
public class EventTriggerService {

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final TriggerPauseRepository pauses;
    final EventReceiptRepository receipts;
    final EventDestinationsProperties destinations;
    final EventTriggerRepository fileTriggers;

    /**
     * Whether a trigger starts processes now. {@code DUPLICATE}: an {@code events.yaml} entry whose id
     * the workflow already has — listed, never fires.
     */
    public enum TriggerState { ACTIVE, PAUSED, INACTIVE, DUPLICATE }

    /**
     * One trigger, for display.
     *
     * @param topic        the destination's topic, or null when the destination is not configured with one
     * @param variables    the variable templates by name; null means "data's top-level members"
     * @param workflowStatus ACTIVE, DISABLED or ARCHIVED (INACTIVE state when not ACTIVE)
     * @param origin       DEFINITION or EVENTS_FILE
     * @param source       the definition id, or the import source key of its {@code events.yaml}
     * @param sourceLabel  "Definition", "events.yaml (git)", "events.yaml (directory)", "events.yaml (classpath)"
     * @param declaredIn   exactly where
     * @param duplicateOf  where the owner of its id is declared, for a file entry that never fires; null otherwise
     */
    public record TriggerView(String workflowId, String workflowName, String triggerId, String destination,
                              String topic, String type, String filter, String businessKey,
                              Map<String, Object> variables, String workflowStatus, boolean workflowPaused,
                              boolean paused, Instant pausedAt, String pausedBy, TriggerState state,
                              String origin, String source, String sourceLabel, String declaredIn, String duplicateOf) {

        /** The key the trigger's pause is kept under: {@code <workflow>/<trigger>}. */
        public String key() {
            return TriggerPause.keyOf(workflowId, triggerId);
        }
    }

    /**
     * Every trigger, by workflow then declaration order — the definition's own first, then its
     * {@code events.yaml} entries; optionally one workflow's, or those matching {@code text}.
     */
    public List<TriggerView> list(String workflowId, String text) {
        var paused = pauses.findAll();
        var views = new ArrayList<TriggerView>();
        for (var entry : catalog()) {
            if (workflowId != null && !workflowId.isBlank() && !workflowId.equals(entry.definition().id())) {
                continue;
            }
            var view = view(entry, paused.get(entry.key()));
            if (matchesText(view, text)) {
                views.add(view);
            }
        }
        return views;
    }

    /** Every trigger the engine knows, definitions and {@code events.yaml} entries alike, in precedence order. */
    public List<EventTriggerCatalog.Entry> catalog() {
        return EventTriggerCatalog.of(definitions(), fileTriggers == null ? List.of() : fileTriggers.findAll());
    }

    /** How many triggers of {@code origin} can fire (not duplicates), for the gauge. */
    public long count(EventTriggerOrigin origin) {
        return catalog().stream().filter(e -> e.origin() == origin && e.fires()).count();
    }

    /** One trigger. */
    public TriggerView get(String workflowId, String triggerId) {
        return list(workflowId, null).stream().filter(v -> v.triggerId().equals(triggerId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No trigger '" + triggerId + "' on workflow '" + workflowId + "'"));
    }

    /** Pauses a trigger: events that arrive while it is paused start nothing, and are not replayed on resume. */
    public TriggerView pause(String workflowId, String triggerId, String by) {
        get(workflowId, triggerId);
        pauses.save(new TriggerPause(TriggerPause.keyOf(workflowId, triggerId), Instant.now(), by));
        return get(workflowId, triggerId);
    }

    /** Resumes a paused trigger. */
    public TriggerView resume(String workflowId, String triggerId) {
        get(workflowId, triggerId);
        pauses.delete(TriggerPause.keyOf(workflowId, triggerId));
        return get(workflowId, triggerId);
    }

    /** The most recent receipts first: what each received event did. */
    public List<EventReceipt> received(int limit, String workflowId) {
        return receipts.recent(limit <= 0 ? 100 : Math.min(limit, 1000), workflowId == null || workflowId.isBlank() ? null : workflowId);
    }

    /**
     * The destinations the engine should listen on: those some definition's trigger, some
     * {@code events.yaml} entry or some {@code WAIT_FOR_MESSAGE} {@code onEvent} names, whatever the
     * definition's status (a disabled definition's running processes still wait).
     */
    public Set<String> listenedDestinations() {
        var names = new LinkedHashSet<String>();
        if (fileTriggers != null) {
            fileTriggers.findAll().forEach(t -> names.add(t.trigger().destination()));
        }
        for (var definition : definitions()) {
            if (definition.triggers() != null) {
                definition.triggers().forEach(t -> names.add(t.destination()));
            }
            for (var step : definition.steps()) {
                if (step.onEvent() != null && StepType.WAIT_FOR_MESSAGE.equals(step.type())) {
                    names.add(step.onEvent().destination());
                }
            }
        }
        names.remove(null);
        return names;
    }

    /** The topic of a destination, or null when it is not configured with one. */
    public String topicOf(String destination) {
        var configured = destinations == null ? null : destinations.getDestinations().get(destination);
        return configured == null || configured.getTopic() == null || configured.getTopic().isBlank() ? null : configured.getTopic();
    }

    private TriggerView view(EventTriggerCatalog.Entry entry, TriggerPause pause) {
        var definition = entry.definition();
        var trigger = entry.trigger();
        var accepts = definition.status().accceptsNewInstances();
        var state = !entry.fires() ? TriggerState.DUPLICATE
                : !accepts ? TriggerState.INACTIVE : pause != null ? TriggerState.PAUSED : TriggerState.ACTIVE;
        return new TriggerView(definition.id(), definition.name(), trigger.id(), trigger.destination(),
                topicOf(trigger.destination()), trigger.type(), trigger.filter(), trigger.businessKey(),
                trigger.variables() == null ? null : new LinkedHashMap<>(trigger.variables()),
                definition.status().name(), definition.paused(), pause != null,
                pause == null ? null : pause.pausedAt(), pause == null ? null : pause.pausedBy(), state,
                entry.origin().name(), entry.source(), entry.sourceLabel(), entry.declaredIn(), entry.duplicateOf());
    }

    private List<WorkflowDefinition> definitions() {
        return workflowDefinitionRepository.findAll().stream()
                .filter(d -> d.id() != null)
                .sorted(Comparator.comparing(WorkflowDefinition::id))
                .toList();
    }

    private static boolean matchesText(TriggerView view, String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        var needle = text.toLowerCase(Locale.ROOT);
        return java.util.stream.Stream.of(view.workflowId(), view.workflowName(), view.triggerId(), view.destination(),
                        view.type(), view.filter(), view.businessKey(), view.sourceLabel(), view.source())
                .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains(needle));
    }
}
