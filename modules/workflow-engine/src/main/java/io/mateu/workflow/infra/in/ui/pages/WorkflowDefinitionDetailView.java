package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Style;
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
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Read-only detail view shown when a workflow definition is selected in the CRUD (the {@code view}
 * action). Definitions are authored as {@code .ec} files (edited with the IDE plugins), so this view
 * never edits them — the only actions are the runtime toggles pause/resume and disable/enable. The
 * name is the view title and a header badge shows the runtime state; the read-only ELK graph sits
 * full-width on top (it already carries the step list visually) with a compact property summary
 * below it.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style(StyleConstants.FULL_WIDTH_WITH_PADDING)
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

    @Hidden
    String id;

    /** Runtime flags, kept for the toolbar visibility rules. */
    @Hidden
    boolean definitionPaused;
    @Hidden
    boolean definitionDisabled;
    @Hidden
    boolean definitionArchived;

    /** Drives the view title via {@link #toString()}; not rendered as a field. */
    @Hidden
    String name;

    /** A field of type {@link Status} is promoted to a header badge (never rendered in the body). */
    Status status;

    // ── Top: full-width read-only ELK graph (its own toolbar/panel hidden in read-only). It shows
    //    every step and how they connect, so a separate step list below would be redundant. ───────
    @Section(value = "Diagram")
    @Label("")
    Element workflow;

    // ── Below the graph: a compact property list (label/value rows) ────────────────
    @Section(value = "Summary", propertyList = true)
    @Label("Version")
    String version;

    @Label("Description")
    String description;

    @Label("Concurrency")
    String concurrency;

    @Label("Cron")
    String cron;

    @Label("Max step executions")
    String maxStepExecutions;

    @Label("Paused")
    String paused;

    public WorkflowDefinitionDetailView load(String workflowId) {
        var def = repository.findById(workflowId).orElseThrow();
        this.id = def.id();
        this.name = def.name();
        this.definitionPaused = def.paused();
        this.definitionDisabled = def.disabled();
        this.definitionArchived = def.archived();
        this.status = runtimeStatus(def.disabled(), def.archived(), def.paused());
        this.version = "v" + def.version();
        this.description = def.description() == null || def.description().isBlank() ? "—" : def.description();
        this.concurrency = def.limitConcurrentExecutions()
                ? "limited to " + def.maxConcurrentExecutions()
                        + (def.enqueueOnLimit() ? " (enqueue on limit)" : " (reject on limit)")
                : "unlimited";
        this.cron = def.cronExpression() == null || def.cronExpression().isBlank()
                ? "—" : def.cronExpression();
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
        var counts = liveProcessCountsByStep(def.id());
        if (!counts.isEmpty()) {
            var overlay = new java.util.HashMap<String, Object>();
            counts.forEach((stepId, c) -> overlay.put(stepId, Map.of("count", c)));
            attrs.put("overlay", toJson(overlay));
        }
        // Give the graph a tall, viewport-sized box: on its own the host falls back to a ~230px
        // min-height, which is too short now that the graph is the primary content of this view.
        this.workflow = Element.builder()
                .name(GRAPH_TAG)
                .attributes(attrs)
                .content("")
                .style("display: block; height: 68vh; min-height: 460px;")
                .build();
        return this;
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
    Map<String, Integer> liveProcessCountsByStep(String definitionId) {
        var counts = new java.util.HashMap<String, Integer>();
        for (var se : stepExecutionRepository.findPendingOrRunning()) {
            if (!definitionId.equals(se.getWorkflowDefinitionId())) continue;
            counts.merge(se.getStepId(), 1, Integer::sum);
        }
        return counts;
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
     * Only the runtime toggles are shown. The built-in CRUD write actions are always hidden:
     * definitions are authored as {@code .ec} files, so "edit" and "Add another" ({@code new}) make
     * no sense here.
     */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "edit", "new" -> true;
            case "disable" -> definitionDisabled;
            case "enable" -> !definitionDisabled;
            case "pause" -> definitionPaused;
            case "resume" -> !definitionPaused;
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
