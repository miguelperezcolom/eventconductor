package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Tab;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.data.DispatchEventData;
import io.mateu.uidl.data.NavigationRequestedPayload;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.data.UICommandType;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.lifecycle.DisableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.EnableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.PauseWorkflowUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ResumeWorkflowUseCase;
import io.mateu.workflow.infra.in.ui.WorkflowHome;
import io.mateu.workflow.infra.in.ui.pages.definitionversions.VersionRow;
import io.mateu.workflow.infra.in.ui.pages.definitionversions.WorkflowDefinitionVersionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import io.mateu.workflow.domain.aggregates.StepExecution;

import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Read-only detail view shown when a workflow definition is selected in the CRUD (the {@code view}
 * action). Definitions are authored as {@code .ec} files (edited with the IDE plugins), so this view
 * never edits them — the only actions are the runtime toggles pause/resume and disable/enable. The
 * name is the view title and a header badge shows the runtime state; the content is split into two
 * tabs — a full-width read-only ELK graph (it already carries the step list visually) and a compact
 * property summary. Tabs (rather than side-by-side zones) keep the graph full-width and avoid a
 * first-render layout shift between the summary and the async-loaded graph.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style(StyleConstants.FULL_WIDTH_WITH_PADDING)
// Use the whole screen width (uncapped) — the graph is a wide canvas, so the default 1408px content
// cap wastes horizontal room.
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class WorkflowDefinitionDetailView implements VisibilitySupplier {

    /** Custom element that renders the workflow as an ELK graph. Shipped by this module. */
    private static final String GRAPH_TAG = "eventconductor-workflow-graph";
    /** Same-origin URL of the component's ESM bundle (served from META-INF/resources). */
    private static final String GRAPH_MODULE = "/eventconductor/workflow-graph.js";

    final WorkflowDefinitionRepository repository;
    final StepExecutionRepository stepExecutionRepository;
    final DisableWorkflowDefinitionUseCase disableWorkflowDefinitionUseCase;
    final EnableWorkflowDefinitionUseCase enableWorkflowDefinitionUseCase;
    final PauseWorkflowUseCase pauseWorkflowUseCase;
    final ResumeWorkflowUseCase resumeWorkflowUseCase;
    /** JPA-only: absent in memory mode, which is why the "Versions" tab is then hidden. */
    final ObjectProvider<WorkflowDefinitionVersionQueryService> versionQueryProvider;
    /** Every schedule targeting this definition — its own, cron.yaml entries, the ones created on the Schedules page. */
    final ObjectProvider<io.mateu.workflow.application.services.ScheduleService> scheduleServiceProvider;
    /** This definition's event triggers, with their pause state. */
    final ObjectProvider<io.mateu.workflow.application.services.EventTriggerService> eventTriggerServiceProvider;

    @Hidden
    String id;

    /** Runtime flags, kept for the toolbar visibility rules. */
    @Hidden
    boolean definitionPaused;
    @Hidden
    boolean definitionDisabled;
    @Hidden
    boolean definitionArchived;
    /**
     * What the definition file declares, as opposed to what an operator has done. A workflow its
     * own definition closes cannot be enabled from here at all, so the button is not offered:
     * the engine refuses it, and a button that only ever produces an error is not a button.
     */
    @Hidden
    boolean declarationBlocks;

    /** Drives the view title via {@link #toString()}; not rendered as a field. */
    @Hidden
    String name;

    /** A field of type {@link Status} is promoted to a header badge (never rendered in the body). */
    Status status;

    // ── "Diagram" tab: full-width read-only ELK graph (its own toolbar/panel hidden in read-only).
    //    It shows every step and how they connect, so a separate step list would be redundant. ─────
    @Tab("Diagram")
    @Label("")
    Element workflow;

    // ── "Summary" tab: the definition's properties as label/value rows. Each field carries the
    //    same @Tab so they merge into one tab strip alongside "Diagram" (a @Section here would
    //    split the summary into its own separate strip). ──────────────────────────────────────────
    @Tab("Summary")
    @Label("Version")
    String version;

    @Tab("Summary")
    @Label("Description")
    String description;

    @Tab("Summary")
    @Label("Tags")
    String tags;

    @Tab("Summary")
    @Label("Retention")
    String retention;

    @Tab("Summary")
    @Label("Concurrency")
    String concurrency;

    @Tab("Summary")
    @Label("Cron")
    String cron;

    @Tab("Summary")
    @Label("Max step executions")
    String maxStepExecutions;

    @Tab("Summary")
    @Label("Paused")
    String paused;

    // ── "Schedules" tab: every scheduled start of this definition — its cronExpression, its own
    //    schedules, the cron.yaml entries and the schedules created on the Schedules page that target
    //    it — with zone, variables, next start and status; each id opens the schedule's page, where it
    //    is operated. Hidden when there is none. ──────────────────────────────────────────────────────
    @Tab("Schedules")
    @Label("")
    List<ScheduleRow> schedules;

    @Hidden
    boolean schedulesAvailable;

    // ── "Triggers" tab: the domain events that start this workflow — destination and its topic, type,
    //    filter, business key, variables and whether each starts processes now (paused and resumed on
    //    Workflow → Event triggers; what the events did is on Workflow → Received events). Hidden when
    //    the definition declares none. ─────────────────────────────────────────────────────────────────
    @Tab("Triggers")
    @Label("")
    List<io.mateu.workflow.infra.in.ui.pages.events.TriggerRow> triggers;

    @Hidden
    boolean triggersAvailable;

    // ── "Versions" tab: the engine-recorded version history of this definition, one row per version
    //    with its creation date and per-version process stats (running/completed/total), plus a
    //    legacy bucket. JPA-only; hidden in memory mode (see isHidden). ─────────────────────────────
    @Tab("Versions")
    @Label("")
    List<VersionRow> versions;

    @Hidden
    boolean versionsAvailable;

    /**
     * How long this definition's finished processes are kept, as the purge will apply it: the
     * definition's own {@code retention}, else the engine defaults — {@code kept forever} when neither
     * says.
     */
    static String retentionOf(io.mateu.workflow.domain.aggregates.WorkflowDefinition def) {
        try {
            var service = io.mateu.uidl.di.MateuBeanProvider.getBean(
                    io.mateu.workflow.application.services.ProcessRetentionService.class);
            if (service != null) {
                return service.policy().describe(def);
            }
        } catch (RuntimeException e) {
            // No engine around this view (a test, a slice): say what the definition says.
        }
        return def.retention() == null ? "—" : def.retention().toString();
    }

    public WorkflowDefinitionDetailView load(String workflowId) {
        var def = repository.findById(workflowId).orElseThrow();
        this.id = def.id();
        this.name = def.name();
        this.definitionPaused = def.paused();
        this.definitionDisabled = def.disabled();
        this.definitionArchived = def.archived();
        this.declarationBlocks = def.declaredStatus() != io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE;
        this.status = runtimeStatus(def.disabled(), def.archived(), def.paused());
        this.version = "v" + def.version();
        this.description = def.description() == null || def.description().isBlank() ? "—" : def.description();
        this.tags = WorkflowDefinitionRow.tagsOf(def);
        this.retention = retentionOf(def);
        this.concurrency = def.limitConcurrentExecutions()
                ? "limited to " + def.maxConcurrentExecutions()
                        + (def.enqueueOnLimit() ? " (enqueue on limit)" : " (reject on limit)")
                : "unlimited";
        this.cron = def.cronExpression() == null || def.cronExpression().isBlank()
                ? "—" : def.cronExpression();
        this.schedules = scheduleRows(def.id());
        this.schedulesAvailable = !schedules.isEmpty();
        if (schedulesAvailable && "—".equals(cron)) {
            this.cron = schedules.size() + " schedule(s) — see the Schedules tab";
        }
        this.triggers = triggerRows(def.id());
        this.triggersAvailable = !triggers.isEmpty();
        this.maxStepExecutions = def.defaultMaxStepExecutions() == 0
                ? "unbounded" : String.valueOf(def.defaultMaxStepExecutions());
        this.paused = def.paused() ? "yes — processes are held" : "no";
        // Rendered through mateu's Element/import mechanism: mateu dynamically imports the module the
        // first time the tag is used, and the custom element upgrades in place. When live processes
        // exist, an overlay badges each node with how many are currently sitting on it.
        var attrs = new java.util.HashMap<String, String>();
        attrs.put("import", GRAPH_MODULE);
        attrs.put("value", toJson(def));
        attrs.put("readonly", "true");
        // Per-step overlay: a live process count + a "stopped/waiting" heat histogram (index = days
        // ago) so the viewer's heatmap toggle + last-N-days slider can recolor and filter entirely
        // client-side. Built from this definition's live step executions across all versions.
        // Live steps AND processes stopped at a step. Without the second the picture is silent
        // about exactly the process worth looking at: one that is RUNNING with nothing live has no
        // step in findPendingOrRunning, so it appears on no node at all.
        var overlay = WorkflowGraphOverlays.overlay(liveForDefinition(def.id()),
                stepExecutionRepository.countStoppedByStep(def.id()));
        if (!overlay.isEmpty()) {
            attrs.put("overlay", toJson(overlay));
        }
        // This is the definition view, where the stopped/waiting heatmap belongs — so offer its
        // toggle unconditionally. The overlay above is empty until some process is live or stopped,
        // and gating the button on it hid the control whenever nothing was running; the operator
        // then had no way to turn the heatmap on precisely when they wanted to check for stalls.
        attrs.put("heatmap", "true");
        // Give the graph a tall, viewport-sized box: on its own the host falls back to a ~230px
        // min-height, which is too short now that the graph is the primary content of this view.
        this.workflow = Element.builder()
                .name(GRAPH_TAG)
                .attributes(attrs)
                .content("")
                .style("display: block; width: 100%; height: 68vh; min-height: 460px;")
                .build();
        // "Versions" tab: only populated when the JPA-only version query service exists.
        var versionQuery = versionQueryProvider.getIfAvailable();
        this.versionsAvailable = versionQuery != null;
        if (versionsAvailable) {
            this.versions = versionQuery.rows(id);
        }
        return this;
    }

    /** Every schedule of the definition {@code definitionId}, in the order the scheduler considers them. */
    List<ScheduleRow> scheduleRows(String definitionId) {
        var service = scheduleServiceProvider == null ? null : scheduleServiceProvider.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        return service.list(definitionId, null).stream().map(ScheduleRow::of).toList();
    }

    /** Every event trigger of the definition {@code definitionId}, in declaration order. */
    List<io.mateu.workflow.infra.in.ui.pages.events.TriggerRow> triggerRows(String definitionId) {
        var service = eventTriggerServiceProvider == null ? null : eventTriggerServiceProvider.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        return service.list(definitionId, null).stream()
                .map(io.mateu.workflow.infra.in.ui.pages.events.TriggerRow::of).toList();
    }

    /**
     * How many live process instances of this definition currently sit on each step — i.e. have a
     * RUNNING or PENDING step execution there. Keyed by step id.
     *
     * <p>Backed by a single indexed query for the whole system's live (PENDING/RUNNING) step
     * executions, filtered in memory to this definition. The working set is bounded by the number
     * of concurrently live steps, not by the total number of processes ever run — the earlier
     * per-process fan-out ({@code findAll()} + one {@code findByProcess} per process) was an N+1
     * that made opening a definition take seconds once thousands of processes had accumulated.
     */
    /** This definition's live (PENDING/RUNNING) step executions, across all versions. */
    private List<StepExecution> liveForDefinition(String definitionId) {
        return stepExecutionRepository.findPendingOrRunning().stream()
                .filter(se -> definitionId.equals(se.getWorkflowDefinitionId()))
                .toList();
    }

    /** How many live process instances of this definition currently sit on each step. Keyed by step id. */
    Map<String, Integer> liveProcessCountsByStep(String definitionId) {
        return WorkflowGraphOverlays.countsByStep(liveForDefinition(definitionId));
    }

    /** Alias kept so callers/tests referring to the window keep compiling. */
    static final int HEAT_WINDOW_DAYS = WorkflowGraphOverlays.HEAT_WINDOW_DAYS;

    /** Per-step heat histogram (days-ago buckets) of this definition's live step executions. */
    Map<String, int[]> stoppedTaskHeatByStep(String definitionId) {
        return WorkflowGraphOverlays.heatByStep(liveForDefinition(definitionId));
    }

    // ── Runtime toolbar: pause/resume and disable/enable. No editing (definitions are .ec files).

    @Toolbar
    public UICommand disable(HttpRequest httpRequest) {
        disableWorkflowDefinitionUseCase.handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    @Toolbar
    public UICommand enable(HttpRequest httpRequest) {
        enableWorkflowDefinitionUseCase.handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    @Toolbar
    public UICommand pause(HttpRequest httpRequest) {
        pauseWorkflowUseCase.handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    @Toolbar
    public UICommand resume(HttpRequest httpRequest) {
        resumeWorkflowUseCase.handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    /**
     * Moves the live processes of one version of this definition onto another — a MIGRATE batch operation
     * (2.31), followed on the Batch operations page. Opens a dialog.
     */
    @Toolbar
    @Label("Migrate instances…")
    public Object migrateInstances() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.dialog(
                "Migrate instances of " + id + " to another version",
                new io.mateu.workflow.infra.in.ui.pages.process.operate.MigrateInstancesForm().forDefinition(id));
    }

    /**
     * Only the runtime toggles are shown. The built-in CRUD write actions are always hidden:
     * definitions are authored as {@code .ec} files, so "edit" and "Add another" ({@code new}) make
     * no sense here.
     */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "edit", "new" -> true;
            case "disable" -> definitionDisabled;
            case "enable" -> !definitionDisabled || declarationBlocks;
            case "pause" -> definitionPaused;
            case "resume" -> !definitionPaused;
            // No version history in memory mode — hide the tab rather than show it empty.
            case "versions" -> !versionsAvailable;
            case "schedules" -> !schedulesAvailable;
            case "triggers" -> !triggersAvailable;
            default -> false;
        };
    }

    private static UICommand navigateToDefinition(String definitionId, HttpRequest httpRequest) {
        return UICommand.builder()
                .type(UICommandType.DispatchEvent)
                .data(new DispatchEventData(
                        "navigation-requested",
                        NavigationRequestedPayload.builder()
                                .route("/workflow/definitions/" + definitionId)
                                .consumedRoute("")
                                .baseUrl(httpRequest.getBaseUrl())
                                .uriPrefix("")
                                .serverSideType(WorkflowHome.class.getName())
                                .build()))
                .build();
    }

    /** The view title (see {@code PageMetadataExtractor.getTitle}: falls back to toString()). */
    @Override
    public String toString() {
        return name != null ? name : "Workflow definition";
    }

    /** Header badge from the runtime flags (archived > disabled > paused > active). */
    private static Status runtimeStatus(boolean disabled, boolean archived, boolean paused) {
        if (archived) return new Status(StatusType.NONE, "Archived");
        if (disabled) return new Status(StatusType.DANGER, "Disabled");
        if (paused) return new Status(StatusType.WARNING, "Paused");
        return new Status(StatusType.SUCCESS, "Active");
    }
}
