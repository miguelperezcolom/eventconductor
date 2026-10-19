package io.mateu.workflow.application.services;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.analysis.EventTriggerRules;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.EventTriggerOrigin;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.EventTriggerCatalog;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * resume them, create, edit and delete the ones created there, and read what the received events did.
 *
 * <p>A trigger is declared in a definition ({@code triggers}), in an {@code events.yaml} next to the
 * definitions, or created here (source {@link EventFileTrigger#API_SOURCE}): {@link EventTriggerCatalog}
 * makes the three one list, keyed by {@code <workflow>/<trigger>}. File-owned triggers are changed in
 * their file and only operated here; one created here is editable and deletable until a file declares
 * the same key — then the file's entry takes it over on import ({@code EventTriggerImporter#reconcile}),
 * its pause carrying over.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventTriggerService {

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final TriggerPauseRepository pauses;
    final EventReceiptRepository receipts;
    final EventDestinationsProperties destinations;
    final EventTriggerRepository fileTriggers;

    /**
     * {@code workflow.security.flow-authorization.enabled}: on, whoever creates or edits a trigger must
     * be allowed to start its workflow — the trigger then starts it as {@code SYSTEM} for every matching
     * event, which is not judged, so this is the moment that counts.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.security.flow-authorization.enabled:false}")
    boolean flowAuthorizationEnabled;

    /** The same service judging callers as {@code enabled} says — for tests. */
    public EventTriggerService withFlowAuthorization(boolean enabled) {
        this.flowAuthorizationEnabled = enabled;
        return this;
    }

    /**
     * Whether a trigger starts processes now. {@code DUPLICATE}: an {@code events.yaml} entry whose id
     * the workflow already has — listed, never fires.
     */
    public enum TriggerState { ACTIVE, PAUSED, INACTIVE, DUPLICATE }

    /**
     * What a create or an edit says. {@code id} is only read on create (an edit keeps the trigger's);
     * {@code variables} null means "data's top-level members", empty means none.
     */
    public record TriggerDraft(String id, String workflowId, String destination, String type, String filter,
                               String businessKey, Map<String, Object> variables,
                               io.mateu.workflow.domain.aggregates.StartPolicy startPolicy) {

        /** A draft with no start policy of its own (the definition's applies, if any). */
        public TriggerDraft(String id, String workflowId, String destination, String type, String filter,
                            String businessKey, Map<String, Object> variables) {
            this(id, workflowId, destination, type, filter, businessKey, variables, null);
        }
    }

    /** No trigger has that key. */
    public static class UnknownTriggerException extends NoSuchElementException {
        public UnknownTriggerException(String workflowId, String triggerId) {
            super("No trigger '" + triggerId + "' on workflow '" + workflowId + "'");
        }
    }

    /** The trigger is declared in a file: changed there, not here. */
    public static class TriggerNotEditableException extends RuntimeException {
        public TriggerNotEditableException(EventTriggerCatalog.Entry entry) {
            super("Trigger '" + entry.trigger().id() + "' of workflow '" + entry.definition().id() + "' is declared in "
                    + entry.declaredIn() + ": change it there. Here it can only be paused or resumed.");
        }
    }

    /** What is wrong with a draft. */
    public static class InvalidTriggerException extends RuntimeException {
        private final List<String> violations;

        public InvalidTriggerException(List<String> violations) {
            super(String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }

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
     * @param editable     whether it can be edited and deleted here (only what was created here)
     */
    public record TriggerView(String workflowId, String workflowName, String triggerId, String destination,
                              String topic, String type, String filter, String businessKey,
                              Map<String, Object> variables, String workflowStatus, boolean workflowPaused,
                              boolean paused, Instant pausedAt, String pausedBy, TriggerState state,
                              String origin, String source, String sourceLabel, String declaredIn, String duplicateOf,
                              boolean editable, io.mateu.workflow.domain.aggregates.StartPolicy startPolicy) {

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
                .orElseThrow(() -> new UnknownTriggerException(workflowId, triggerId));
    }

    /**
     * Creates a trigger owned by the engine (source UI/API). It needs an id — a file entry may have one
     * derived, a trigger created here is named — unique within its workflow across every declaration.
     *
     * @throws InvalidTriggerException when the draft breaks a rule
     */
    public TriggerView create(TriggerDraft draft) {
        return create(draft, null);
    }

    /**
     * {@link #create(TriggerDraft)} on behalf of {@code caller}, who — with flow authorization on —
     * must be allowed to start the trigger's workflow.
     */
    public TriggerView create(TriggerDraft draft, io.mateu.workflow.security.AuthorizationContext caller) {
        var violations = new ArrayList<String>();
        var id = draft.id() == null ? null : draft.id().trim();
        var workflowId = draft.workflowId() == null ? null : draft.workflowId().trim();
        if (id == null || id.isEmpty()) {
            violations.add("a trigger created here needs an id (e.g. on-booking-created)");
        } else if (workflowId != null && !workflowId.isEmpty()) {
            EventTriggerCatalog.owner(catalog(), workflowId, id).ifPresent(owner -> violations.add(
                    "id '" + id + "' is already used by the trigger declared in " + owner.declaredIn()));
        }
        var trigger = validated(id, draft, violations);
        authorize(workflowId, caller, "event trigger '" + id + "'");
        fileTriggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, workflowId, trigger));
        log.info("Event trigger '{}' created for workflow '{}' ({} on {})", id, workflowId, trigger.type(), trigger.destination());
        EventTriggerImporter.warnOfSharedBusinessKeys(catalog());
        return get(workflowId, id);
    }

    /**
     * Edits a trigger created here: destination, type, filter, business key and variables are replaced;
     * the key never changes (the workflow of the draft must be the trigger's).
     *
     * @throws TriggerNotEditableException for a trigger declared in a file
     */
    public TriggerView update(String workflowId, String triggerId, TriggerDraft draft) {
        return update(workflowId, triggerId, draft, null);
    }

    /**
     * {@link #update(String, String, TriggerDraft)} on behalf of {@code caller}, who — with flow
     * authorization on — must be allowed to start the trigger's workflow.
     */
    public TriggerView update(String workflowId, String triggerId, TriggerDraft draft,
                              io.mateu.workflow.security.AuthorizationContext caller) {
        var entry = editableOwner(workflowId, triggerId);
        var violations = new ArrayList<String>();
        if (draft.workflowId() != null && !draft.workflowId().isBlank() && !draft.workflowId().trim().equals(workflowId)) {
            violations.add("a trigger's workflow never changes: delete it and create one for '" + draft.workflowId() + "'");
        }
        var trigger = validated(entry.trigger().id(), new TriggerDraft(triggerId, workflowId, draft.destination(), draft.type(),
                draft.filter(), draft.businessKey(), draft.variables(), draft.startPolicy()), violations);
        authorize(workflowId, caller, "event trigger '" + triggerId + "'");
        fileTriggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, workflowId, trigger));
        log.info("Event trigger '{}' of workflow '{}' updated ({} on {})", triggerId, workflowId, trigger.type(), trigger.destination());
        EventTriggerImporter.warnOfSharedBusinessKeys(catalog());
        return get(workflowId, triggerId);
    }

    /** Deletes a trigger created here, with its pause. */
    public void delete(String workflowId, String triggerId) {
        var entry = editableOwner(workflowId, triggerId);
        fileTriggers.deleteApiTriggers(List.of(entry.key()));
        pauses.delete(entry.key());
        log.info("Event trigger '{}' of workflow '{}' deleted", triggerId, workflowId);
    }

    /**
     * The trigger as an {@code events.yaml} entry, id included: committed next to the definitions, the
     * file owns the trigger from the next import on.
     */
    public String exportYaml(String workflowId, String triggerId) {
        var entry = EventTriggerCatalog.owner(catalog(), workflowId, triggerId)
                .orElseThrow(() -> new UnknownTriggerException(workflowId, triggerId));
        var trigger = entry.trigger();
        var yaml = new StringBuilder()
                .append("# events.yaml next to the workflow definitions. Once imported, this entry owns trigger '")
                .append(trigger.id()).append("' of workflow '").append(workflowId).append("'.\n")
                .append("- id: ").append(ScheduleService.scalar(trigger.id())).append('\n')
                .append("  destination: ").append(ScheduleService.scalar(trigger.destination())).append('\n')
                .append("  type: ").append(ScheduleService.quoted(trigger.type())).append('\n')
                .append("  workflow: ").append(ScheduleService.scalar(workflowId)).append('\n');
        if (trigger.filter() != null) {
            yaml.append("  filter: ").append(ScheduleService.quoted(trigger.filter())).append('\n');
        }
        if (trigger.businessKey() != null) {
            yaml.append("  businessKey: ").append(ScheduleService.quoted(trigger.businessKey())).append('\n');
        }
        if (trigger.variables() != null) {
            if (trigger.variables().isEmpty()) {
                yaml.append("  variables: {}\n");
            } else {
                yaml.append("  variables:\n");
                trigger.variables().forEach((name, value) -> yaml.append("    ").append(ScheduleService.scalar(name))
                        .append(": ").append(yamlValue(value)).append('\n'));
            }
        }
        return yaml.toString();
    }

    /** A template value as YAML: text quoted when needed, numbers and booleans as they are, structures as JSON. */
    private static String yamlValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return ScheduleService.scalar(s);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return ScheduleService.quoted(String.valueOf(value));
        }
    }

    private void authorize(String workflowId, io.mateu.workflow.security.AuthorizationContext caller, String context) {
        if (!flowAuthorizationEnabled || workflowId == null) {
            return;
        }
        workflowDefinitionRepository.findById(workflowId).ifPresent(definition ->
                io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase
                        .refuseIfCallerMayNotStart(definition, caller, context));
    }

    private EventTriggerCatalog.Entry editableOwner(String workflowId, String triggerId) {
        var entry = EventTriggerCatalog.owner(catalog(), workflowId, triggerId)
                .orElseThrow(() -> new UnknownTriggerException(workflowId, triggerId));
        if (!entry.editable()) {
            throw new TriggerNotEditableException(entry);
        }
        return entry;
    }

    /** The trigger a draft describes, once every rule holds; throws with every violation otherwise. */
    private EventTrigger validated(String id, TriggerDraft draft, List<String> violations) {
        var workflowId = draft.workflowId() == null ? null : draft.workflowId().trim();
        if (workflowId == null || workflowId.isEmpty()) {
            violations.add("a trigger needs the id of the workflow it starts ('workflowId')");
        } else if (workflowDefinitionRepository.findById(workflowId).isEmpty()) {
            violations.add("unknown workflow '" + workflowId + "'");
        }
        violations.addAll(EventTriggerRules.triggerViolations(id, draft.destination(), draft.type(), null));
        // The same template check a definition's triggers get: a filter or template that does not parse
        // would only fail later, as an ERROR receipt on every event.
        violations.addAll(io.mateu.workflow.domain.aggregates.WorkflowDefinition.eventTemplateProblems(
                draft.filter(), draft.businessKey(), draft.variables(), "the trigger"));
        if (draft.startPolicy() != null) {
            violations.addAll(draft.startPolicy().violations("startPolicy"));
        }
        if (!violations.isEmpty()) {
            throw new InvalidTriggerException(violations);
        }
        return new EventTrigger(id, draft.destination(), draft.type(), draft.filter(), draft.businessKey(), draft.variables(),
                draft.startPolicy());
    }

    /** Pauses a trigger: events that arrive while it is paused start nothing, and are not replayed on resume. */
    public TriggerView pause(String workflowId, String triggerId, String by) {
        get(workflowId, triggerId);
        pauses.save(new TriggerPause(TriggerPause.keyOf(workflowId, triggerId), EngineClock.instant(), by));
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
     * {@code events.yaml} entry or some {@code WAIT_FOR_MESSAGE} {@code onEvent} (or {@code awaitReply.onEvent}) names, whatever the
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
                var wait = step.awaitsMessage() ? step.messageWait().onEvent() : null;
                if (wait != null) {
                    names.add(wait.destination());
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
                entry.origin().name(), entry.source(), entry.sourceLabel(), entry.declaredIn(), entry.duplicateOf(),
                entry.editable(), trigger.startPolicy());
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
