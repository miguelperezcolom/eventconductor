package io.mateu.workflow.application.services;

import io.mateu.workflow.analysis.ScheduleRules;
import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.aggregates.ScheduleOrigin;
import io.mateu.workflow.domain.aggregates.SchedulePause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.CronDescriptions;
import io.mateu.workflow.domain.services.ScheduleCatalog;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Schedules as something to consult and operate, whoever asks — the admin UI, the REST API, the MCP
 * tools all come through here.
 *
 * <ul>
 *   <li><b>Consult</b> every schedule, wherever declared, by its id ({@link Schedule}).</li>
 *   <li><b>Operate</b> any of them: pause and resume (kept by id, apart from the schedule, so a pause
 *       survives the re-imports of a file-owned one), and run now — one start, immediately, through
 *       the same door the scheduler uses.</li>
 *   <li><b>Create, edit, delete</b> the ones created through the engine. A schedule a file declares
 *       (a definition, a {@code cron.yaml}) is changed by changing the file.</li>
 *   <li><b>Promote</b> one to a file: {@link #exportYaml} gives the {@code cron.yaml} entry, id
 *       included. Once committed and imported, the file's entry takes the schedule over — see
 *       {@link CronScheduleImporter#reconcile()}.</li>
 * </ul>
 */
@Service
@Slf4j
public class ScheduleService {

    /** How many upcoming starts a schedule is shown with. */
    public static final int NEXT_FIRES = 5;

    /** Upcoming starts: offset and zone, so a reader can show them in the zone they are read in. */
    private static final DateTimeFormatter FIRE = DateTimeFormatter.ISO_ZONED_DATE_TIME;
    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern PLAIN_YAML = Pattern.compile("[A-Za-z_][A-Za-z0-9._/-]*");
    private static final java.util.Set<String> YAML_WORDS = java.util.Set.of(
            "true", "false", "yes", "no", "on", "off", "null", "y", "n");

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final CronScheduleRepository cronScheduleRepository;
    final SchedulePauseRepository schedulePauseRepository;
    /** Where run-now sends its start: {@link IngressRouter#route}, as the scheduler does. */
    final Consumer<ProcessCreationRequested> starts;
    /** The clock; replaceable by tests ({@link #withClock}). */
    private Supplier<Instant> clock = Instant::now;

    /**
     * {@code workflow.security.flow-authorization.enabled}: on, whoever creates, edits or runs a
     * schedule now must be allowed to start its workflow — the schedule then starts it as
     * {@link AuthorizationContext#SYSTEM}, which is not judged, so this is the moment that counts.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.security.flow-authorization.enabled:false}")
    boolean flowAuthorizationEnabled;

    /** The same service judging callers as {@code enabled} says — for tests. */
    public ScheduleService withFlowAuthorization(boolean enabled) {
        this.flowAuthorizationEnabled = enabled;
        return this;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleService(WorkflowDefinitionRepository workflowDefinitionRepository,
                           CronScheduleRepository cronScheduleRepository,
                           SchedulePauseRepository schedulePauseRepository,
                           IngressRouter ingressRouter) {
        this(workflowDefinitionRepository, cronScheduleRepository, schedulePauseRepository,
                (Consumer<ProcessCreationRequested>) ingressRouter::route);
    }

    public ScheduleService(WorkflowDefinitionRepository workflowDefinitionRepository,
                           CronScheduleRepository cronScheduleRepository,
                           SchedulePauseRepository schedulePauseRepository,
                           Consumer<ProcessCreationRequested> starts) {
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.cronScheduleRepository = cronScheduleRepository;
        this.schedulePauseRepository = schedulePauseRepository;
        this.starts = starts;
    }

    /** The same service reading the time from {@code clock} — for tests that need the next starts to hold still. */
    public ScheduleService withClock(Supplier<Instant> clock) {
        this.clock = clock;
        return this;
    }

    // ── What callers send and get back ───────────────────────────────────────────────────────────

    /** A schedule to create, or what an edit changes it to. The id is ignored on an edit: it never changes. */
    public record ScheduleDraft(String id, String workflowId, String cron, String zone, Map<String, String> variables) {
    }

    /** Whether a schedule fires, and if not why. */
    public enum ScheduleState {
        /** Fires at each occurrence. */
        ACTIVE,
        /** Paused by an operator. */
        PAUSED,
        /** Its workflow is unknown, disabled or archived: nothing to start. */
        WORKFLOW_INACTIVE,
        /** Its cron or zone cannot be read (a definition loaded without validation). */
        INVALID
    }

    /**
     * One schedule, as shown.
     *
     * @param id            the schedule's id (declared, or derived — see {@link Schedule})
     * @param idDeclared    whether the id was declared rather than derived from the content
     * @param description   the cron in words, when it is a common shape; null otherwise
     * @param zone          the zone it declares, null for none
     * @param effectiveZone the zone it is read in
     * @param origin        DEFINITION, CRON_FILE or API
     * @param source        the definition id, the import source key, or {@code ui}
     * @param sourceLabel   "Definition", "cron.yaml (git)", "UI/API"…
     * @param declaredIn    exactly where
     * @param editable      whether it can be edited and deleted here (only what was created here)
     * @param nextFires     the next {@value #NEXT_FIRES} starts, ISO-8601 with offset and zone
     *                      ({@code 2026-10-15T09:00:00+02:00[Europe/Madrid]})
     * @param alsoDeclaredIn other declarations of the same id, which never fire
     */
    public record ScheduleView(String id, boolean idDeclared, String workflowId, String workflowName,
                               String cron, String description, String zone, String effectiveZone,
                               Map<String, String> variables, String origin, String source, String sourceLabel,
                               String declaredIn, boolean editable, boolean paused, String pausedAt,
                               String pausedBy, ScheduleState state, List<String> nextFires,
                               List<String> alsoDeclaredIn) {
    }

    /** What run-now started. */
    public record RunNowResult(String scheduleId, String workflowId, String businessKey) {
    }

    /** No schedule has that id. */
    public static class UnknownScheduleException extends RuntimeException {
        public UnknownScheduleException(String id) {
            super("No schedule with id '" + id + "'");
        }
    }

    /** The schedule is owned by a file: it is changed there, not here. */
    public static class ScheduleNotEditableException extends RuntimeException {
        public ScheduleNotEditableException(Schedule schedule) {
            super("Schedule '" + schedule.id() + "' is declared in " + schedule.declaredIn()
                    + ": change it there. Here it can only be paused, resumed or run now.");
        }
    }

    /** What was asked for breaks a rule; every broken rule is listed. */
    public static class InvalidScheduleException extends RuntimeException {
        private final List<String> violations;

        public InvalidScheduleException(List<String> violations) {
            super(String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }

    // ── Consult ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Every schedule (one per id), optionally only those of one workflow and those whose id, workflow,
     * cron, source or variables contain {@code searchText}.
     */
    public List<ScheduleView> list(String workflowId, String searchText) {
        var definitions = definitionsById();
        var catalog = catalog(definitions);
        var pauses = schedulePauseRepository.findAll();
        var now = clock.get();
        var needle = searchText == null || searchText.isBlank() ? null : searchText.trim().toLowerCase(Locale.ROOT);
        return catalog.stream()
                .filter(s -> s.duplicateOf() == null)
                .filter(s -> workflowId == null || workflowId.isBlank() || workflowId.equals(s.workflowId()))
                .map(s -> view(s, catalog, definitions, pauses.get(s.id()), now))
                .filter(v -> needle == null || searchable(v).contains(needle))
                .toList();
    }

    /** One schedule by id. */
    public ScheduleView get(String id) {
        var definitions = definitionsById();
        var catalog = catalog(definitions);
        var schedule = owner(catalog, id);
        return view(schedule, catalog, definitions, schedulePauseRepository.findAll().get(schedule.id()), clock.get());
    }

    /** The next {@code count} starts of a cron read in a zone — for a form showing what it will do. */
    public List<String> preview(String cron, String zone, int count) {
        var violations = ScheduleRules.violations(cron, zone, null);
        if (!violations.isEmpty()) {
            throw new InvalidScheduleException(violations);
        }
        var probe = new Schedule("preview", true, ScheduleOrigin.API, Schedule.API_SOURCE, 0, "preview",
                new CronSchedule(cron, zone, null), false, null);
        return probe.nextFires(clock.get(), count).stream().map(FIRE::format).toList();
    }

    // ── Create, edit, delete — only what was created here ────────────────────────────────────────

    /** Creates a schedule owned by the engine, for an unidentified caller. See {@link #create(ScheduleDraft, AuthorizationContext)}. */
    public ScheduleView create(ScheduleDraft draft) {
        return create(draft, null);
    }

    /**
     * Creates a schedule owned by the engine. Its id must be free: one schedule per id.
     *
     * @param caller who asks; with flow authorization on they must be allowed to start the workflow
     */
    public ScheduleView create(ScheduleDraft draft, AuthorizationContext caller) {
        var definitions = definitionsById();
        var catalog = catalog(definitions);
        var violations = new ArrayList<String>();
        var id = draft.id() == null ? null : draft.id().trim();
        if (id == null || id.isEmpty()) {
            violations.add("a schedule created here needs an id (e.g. nightly-report)");
        } else {
            ScheduleCatalog.byId(catalog).values().stream()
                    .filter(s -> s.id().equals(id))
                    .findFirst()
                    .ifPresent(s -> violations.add("id '" + id + "' is already used by the schedule declared in "
                            + s.declaredIn()));
        }
        var schedule = validated(id, draft, definitions, violations);
        authorize(definitions.get(draft.workflowId().trim()), caller, "schedule '" + id + "'");
        cronScheduleRepository.save(new CronFileSchedule(Schedule.API_SOURCE, 0, draft.workflowId().trim(), schedule));
        log.info("Schedule '{}' created for workflow '{}' ({})", id, draft.workflowId(), schedule.cron());
        return get(id);
    }

    /** Changes a schedule, for an unidentified caller. See {@link #update(String, ScheduleDraft, AuthorizationContext)}. */
    public ScheduleView update(String id, ScheduleDraft draft) {
        return update(id, draft, null);
    }

    /**
     * Changes a schedule created here: workflow, cron, zone, variables. Its id stays.
     *
     * @param caller who asks; with flow authorization on they must be allowed to start both the
     *               workflow the schedule starts now and the one it is changed to
     */
    public ScheduleView update(String id, ScheduleDraft draft, AuthorizationContext caller) {
        var definitions = definitionsById();
        var schedule = editableOwner(catalog(definitions), id);
        var updated = validated(schedule.id(), draft, definitions, new ArrayList<>());
        authorize(definitions.get(schedule.workflowId()), caller, "schedule '" + id + "'");
        authorize(definitions.get(draft.workflowId().trim()), caller, "schedule '" + id + "'");
        cronScheduleRepository.save(new CronFileSchedule(Schedule.API_SOURCE, 0, draft.workflowId().trim(), updated));
        log.info("Schedule '{}' updated: workflow '{}' ({})", id, draft.workflowId(), updated.cron());
        return get(id);
    }

    /** Deletes a schedule created here, and its pause. */
    public void delete(String id) {
        var schedule = editableOwner(catalog(definitionsById()), id);
        cronScheduleRepository.deleteApiSchedules(List.of(schedule.id()));
        schedulePauseRepository.deleteAll(List.of(schedule.id()));
        log.info("Schedule '{}' deleted", id);
    }

    // ── Operate — any schedule ───────────────────────────────────────────────────────────────────

    /** Pauses a schedule: it does not fire until resumed. Already paused is fine. */
    public ScheduleView pause(String id, String by) {
        var schedule = owner(catalog(definitionsById()), id);
        schedulePauseRepository.save(new SchedulePause(schedule.id(), clock.get(), by));
        log.info("Schedule '{}' paused{}", id, by == null ? "" : " by " + by);
        return get(id);
    }

    /**
     * Resumes a paused schedule. Occurrences that fell due while it was paused are not replayed: it
     * fires again from its next occurrence.
     */
    public ScheduleView resume(String id) {
        var schedule = owner(catalog(definitionsById()), id);
        schedulePauseRepository.deleteAll(List.of(schedule.id()));
        log.info("Schedule '{}' resumed", id);
        return get(id);
    }

    /**
     * Starts one process now with the schedule's workflow and variables — paused or not — exactly as an
     * occurrence would (the engine acting for itself, {@link AuthorizationContext#SYSTEM}), under the
     * business key {@code <workflow>-cron-manual-<id>-<instant>}, which no occurrence ever has.
     */
    public RunNowResult runNow(String id, String by) {
        return runNow(id, by, null);
    }

    /**
     * {@link #runNow(String, String)}, the start still made as {@link AuthorizationContext#SYSTEM} but
     * only once {@code caller} is shown to be allowed to start the workflow (flow authorization on).
     */
    public RunNowResult runNow(String id, String by, AuthorizationContext caller) {
        var definitions = definitionsById();
        var schedule = owner(catalog(definitions), id);
        var definition = definitions.get(schedule.workflowId());
        if (definition == null || !definition.status().accceptsNewInstances()) {
            throw new InvalidScheduleException(List.of("workflow '" + schedule.workflowId()
                    + "' is unknown or does not accept new instances"));
        }
        authorize(definition, caller, "run-now of schedule '" + id + "'");
        var businessKey = schedule.manualBusinessKey(clock.get());
        var variables = schedule.schedule().variablesOrEmpty().entrySet().stream()
                .map(e -> new Variable(e.getKey(), e.getValue()))
                .toList();
        log.info("Schedule '{}' run now{}: workflow '{}', key {}", id, by == null ? "" : " by " + by,
                schedule.workflowId(), businessKey);
        starts.accept(new ProcessCreationRequested(schedule.workflowId(), businessKey, variables, null,
                AuthorizationContext.SYSTEM));
        return new RunNowResult(schedule.id(), schedule.workflowId(), businessKey);
    }

    /**
     * The schedule as a {@code cron.yaml} entry, id included, ready to paste into the list of a
     * {@code cron.yaml} next to the definitions. Once that file is imported its entry is this schedule —
     * same id, so the same pause and the same business keys — and the copy kept here is deleted.
     */
    public String exportYaml(String id) {
        var schedule = owner(catalog(definitionsById()), id);
        var cron = schedule.schedule();
        var yaml = new StringBuilder()
                .append("# cron.yaml next to the workflow definitions. Once imported, this entry owns schedule '")
                .append(schedule.id()).append("'.\n")
                .append("- id: ").append(scalar(schedule.id())).append('\n')
                .append("  cron: ").append(quoted(cron.cron())).append('\n')
                .append("  workflow: ").append(scalar(schedule.workflowId())).append('\n');
        if (cron.zone() != null) {
            yaml.append("  zone: ").append(scalar(cron.zone())).append('\n');
        }
        if (!cron.variablesOrEmpty().isEmpty()) {
            yaml.append("  variables:\n");
            cron.variablesOrEmpty().forEach((name, value) ->
                    yaml.append("    ").append(scalar(name)).append(": ").append(scalar(value)).append('\n'));
        }
        return yaml.toString();
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────────────

    private void authorize(WorkflowDefinition definition, AuthorizationContext caller, String context) {
        if (flowAuthorizationEnabled && definition != null) {
            io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase
                    .refuseIfCallerMayNotStart(definition, caller, context);
        }
    }

    private Map<String, WorkflowDefinition> definitionsById() {
        return workflowDefinitionRepository.findAll().stream()
                .filter(d -> d.id() != null)
                .collect(Collectors.toMap(WorkflowDefinition::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private List<Schedule> catalog(Map<String, WorkflowDefinition> definitions) {
        return ScheduleCatalog.of(definitions.values(), cronScheduleRepository.findAll());
    }

    private static Schedule owner(List<Schedule> catalog, String id) {
        var schedule = id == null ? null : ScheduleCatalog.byId(catalog).get(id.trim());
        if (schedule == null) {
            throw new UnknownScheduleException(id);
        }
        return schedule;
    }

    private static Schedule editableOwner(List<Schedule> catalog, String id) {
        var schedule = owner(catalog, id);
        if (!schedule.editable()) {
            throw new ScheduleNotEditableException(schedule);
        }
        return schedule;
    }

    /** The draft as a schedule with {@code id}, or every rule it breaks (with {@code violations} so far). */
    private static CronSchedule validated(String id, ScheduleDraft draft, Map<String, WorkflowDefinition> definitions,
                                          List<String> violations) {
        var workflowId = draft.workflowId() == null ? null : draft.workflowId().trim();
        if (workflowId == null || workflowId.isEmpty()) {
            violations.add("a schedule needs the id of the workflow it starts");
        } else {
            var definition = definitions.get(workflowId);
            if (definition == null) {
                violations.add("workflow '" + workflowId + "' is unknown");
            } else if (!definition.status().accceptsNewInstances()) {
                violations.add("workflow '" + workflowId + "' is " + definition.status().name().toLowerCase(Locale.ROOT)
                        + " and does not accept new instances");
            }
        }
        violations.addAll(ScheduleRules.violations(id, draft.cron(), draft.zone(), null));
        var variables = draft.variables() == null ? Map.<String, String>of() : draft.variables();
        variables.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                violations.add("a variable needs a name");
            }
            if (value == null) {
                violations.add("variable '" + name + "' needs a value");
            }
        });
        try {
            InputLimits.checkIdentifier(id, "id");
            InputLimits.checkNamedValues(new ArrayList<>(variables.entrySet()), Map.Entry::getKey, Map.Entry::getValue,
                    "schedule '" + id + "'");
        } catch (InputLimits.InputRejectedException e) {
            violations.add(e.getMessage());
        }
        if (!violations.isEmpty()) {
            throw new InvalidScheduleException(violations);
        }
        return new CronSchedule(id, draft.cron(), draft.zone(), variables.isEmpty() ? null : variables);
    }

    private ScheduleView view(Schedule schedule, List<Schedule> catalog, Map<String, WorkflowDefinition> definitions,
                              SchedulePause pause, Instant now) {
        var definition = definitions.get(schedule.workflowId());
        var cron = schedule.schedule();
        ScheduleState state;
        if (!schedule.valid()) {
            state = ScheduleState.INVALID;
        } else if (pause != null) {
            state = ScheduleState.PAUSED;
        } else if (definition == null || !definition.status().accceptsNewInstances()) {
            state = ScheduleState.WORKFLOW_INACTIVE;
        } else {
            state = ScheduleState.ACTIVE;
        }
        var also = catalog.stream()
                .filter(s -> s.duplicateOf() != null && s.id().equals(schedule.id()))
                .map(Schedule::declaredIn)
                .toList();
        return new ScheduleView(schedule.id(), schedule.idDeclared(), schedule.workflowId(),
                definition == null ? null : definition.name(), cron.cron(),
                CronDescriptions.describe(cron.cron()).orElse(null), cron.zone(),
                schedule.effectiveZone().map(java.time.ZoneId::getId).orElse(null),
                cron.variablesOrEmpty(), schedule.origin().name(), schedule.source(), schedule.sourceLabel(),
                schedule.declaredIn(), schedule.editable(), pause != null,
                pause == null ? null : INSTANT.format(pause.pausedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).atOffset(java.time.ZoneOffset.UTC)),
                pause == null ? null : pause.pausedBy(), state,
                schedule.nextFires(now, NEXT_FIRES).stream().map(FIRE::format).toList(), also);
    }

    private static String searchable(ScheduleView view) {
        return String.join(" ", view.id(), view.workflowId(), view.workflowName() == null ? "" : view.workflowName(),
                view.cron() == null ? "" : view.cron(), view.sourceLabel(), view.declaredIn(),
                view.variables().toString()).toLowerCase(Locale.ROOT);
    }

    /** A YAML scalar: as is when that reads back as the same string, double-quoted otherwise. */
    static String scalar(String value) {
        if (PLAIN_YAML.matcher(value).matches() && !YAML_WORDS.contains(value.toLowerCase(Locale.ROOT))) {
            return value;
        }
        return quoted(value);
    }

    /** Double-quoted, escaped as JSON — which YAML reads the same. */
    static String quoted(String value) {
        var escaped = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
