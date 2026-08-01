package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Zone;
import io.mateu.uidl.annotations.Zones;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.FileDownload;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.data.DispatchEventData;
import io.mateu.uidl.data.NavigationRequestedPayload;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.data.UICommandType;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.application.usecases.export.ExportWorkflowDefinitionToYamlUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ArchiveWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.DisableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.EnableWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.PauseWorkflowUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ReactivateWorkflowDefinitionUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ResumeWorkflowUseCase;
import io.mateu.workflow.application.usecases.workingcopy.CreateWorkingCopyUseCase;
import io.mateu.workflow.application.usecases.workingcopy.PromoteWorkingCopyUseCase;
import io.mateu.workflow.infra.in.ui.WorkflowHome;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Read-only detail view shown when a workflow definition is selected in the CRUD (the {@code view}
 * action, separate from the editor). The definition name is the view title and its lifecycle status
 * a header badge; the left zone is a compact property list, the right zone a read-only ELK graph of
 * the workflow, and the steps a full-width band below. Editing happens through the separate editor.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style(StyleConstants.FULL_WIDTH_WITH_PADDING)
@Zones({
        @Zone(name = "info", width = "50%"),
        @Zone(name = "graph", width = "50%")
})
public class WorkflowDefinitionDetailView implements VisibilitySupplier {

    /** Custom element that renders the workflow as an ELK graph. Shipped by this module. */
    private static final String GRAPH_TAG = "eventconductor-workflow-graph";
    /** Same-origin URL of the component's ESM bundle (served from META-INF/resources). */
    private static final String GRAPH_MODULE = "/eventconductor/workflow-graph.js";

    final WorkflowDefinitionRepository repository;
    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final ExportWorkflowDefinitionToYamlUseCase exportWorkflowDefinitionToYamlUseCase;
    final PromoteWorkingCopyUseCase promoteWorkingCopyUseCase;
    final CreateWorkingCopyUseCase createWorkingCopyUseCase;
    final DisableWorkflowDefinitionUseCase disableWorkflowDefinitionUseCase;
    final EnableWorkflowDefinitionUseCase enableWorkflowDefinitionUseCase;
    final ReactivateWorkflowDefinitionUseCase reactivateWorkflowDefinitionUseCase;
    final ArchiveWorkflowDefinitionUseCase archiveWorkflowDefinitionUseCase;
    final PauseWorkflowUseCase pauseWorkflowUseCase;
    final ResumeWorkflowUseCase resumeWorkflowUseCase;

    @Hidden
    String id;

    /** Domain lifecycle status, kept for the toolbar visibility rules (the badge shows {@link #status}). */
    @Hidden
    WorkflowDefinitionStatus definitionStatus;

    /** Runtime pause flag, kept for the pause/resume toolbar visibility rules. */
    @Hidden
    boolean definitionPaused;

    @Hidden
    String draftOfId;

    /** Drives the view title via {@link #toString()}; not rendered as a field. */
    @Hidden
    String name;

    /** A field of type {@link Status} is promoted to a header badge (never rendered in the body). */
    Status status;

    // ── Left zone: a compact property list (label/value rows) ──────────────────────
    @Section(value = "Summary", zone = "info", propertyList = true)
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

    // ── Right zone: read-only ELK graph (its own toolbar/panel hidden in read-only) ─
    @Section(value = "Diagram", zone = "graph")
    Element workflow;

    // ── Full-width band below both zones: the steps ────────────────────────────────
    @Section(value = "Steps")
    @Label("")
    List<Step> steps;

    public WorkflowDefinitionDetailView load(String workflowId) {
        var def = repository.findById(workflowId).orElseThrow();
        this.id = def.id();
        this.name = def.name();
        this.definitionStatus = def.status();
        this.draftOfId = def.draftOfId();
        this.status = new Status(statusType(def.status()), String.valueOf(def.status()));
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
        this.definitionPaused = def.paused();
        this.paused = def.paused() ? "yes — processes are held" : "no";
        this.steps = def.steps();
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
        this.workflow = new Element(GRAPH_TAG, attrs, "");
        return this;
    }

    /**
     * How many live (running/pending/paused) process instances of this definition currently sit on
     * each step — i.e. have a RUNNING or PENDING step execution there. Keyed by step id.
     */
    private Map<String, Integer> liveProcessCountsByStep(String definitionId) {
        var counts = new java.util.HashMap<String, Integer>();
        for (var process : processRepository.findAll()) {
            if (!definitionId.equals(process.getWorkflowDefinitionId())) continue;
            var st = process.getStatus();
            if (st != ProcessStatus.RUNNING && st != ProcessStatus.PENDING && st != ProcessStatus.PAUSED) continue;
            for (var se : stepExecutionRepository.findByProcess(process)) {
                if (se.getStatus() == StepExecutionStatus.RUNNING || se.getStatus() == StepExecutionStatus.PENDING) {
                    counts.merge(se.getStepId(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    // ── Lifecycle toolbar: same actions and visibility rules as the WorkflowDefinition record
    // form. They must live here too because the CRUD's "view" action renders this read-only
    // view, not the record form — without them an ACTIVE definition could not be disabled or
    // copied from the UI at all. ──

    @Toolbar
    public UICommand promoteToProduction(HttpRequest httpRequest) {
        var promotedId = promoteWorkingCopyUseCase.handle(id);
        return navigateToDefinition(promotedId, httpRequest);
    }

    @Toolbar
    public UICommand createWorkingCopy(HttpRequest httpRequest) {
        var copyId = createWorkingCopyUseCase.handle(id);
        return navigateToDefinition(copyId, httpRequest);
    }

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
    public UICommand reactivate(HttpRequest httpRequest) {
        reactivateWorkflowDefinitionUseCase.handle(id);
        return navigateToDefinition(id, httpRequest);
    }

    @Toolbar
    public UICommand archive(HttpRequest httpRequest) {
        archiveWorkflowDefinitionUseCase.handle(id);
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

    /** Mirrors {@code WorkflowDefinition.isHidden} so both surfaces enforce the same lifecycle. */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "edit" -> definitionStatus == WorkflowDefinitionStatus.ACTIVE;
            case "promoteToProduction" -> definitionStatus != WorkflowDefinitionStatus.DRAFT;
            case "createWorkingCopy" -> definitionStatus != WorkflowDefinitionStatus.ACTIVE;
            case "disable" -> definitionStatus != WorkflowDefinitionStatus.ACTIVE;
            case "enable" -> definitionStatus != WorkflowDefinitionStatus.DISABLED;
            case "reactivate" -> definitionStatus != WorkflowDefinitionStatus.ARCHIVED;
            case "archive" -> definitionStatus == WorkflowDefinitionStatus.ACTIVE
                    || definitionStatus == WorkflowDefinitionStatus.ARCHIVED;
            // Runtime pause is orthogonal to the lifecycle status: only the flag decides.
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

    @Toolbar
    @Label("Export YAML")
    public UICommand exportYaml() {
        var export = exportWorkflowDefinitionToYamlUseCase.handle(id);
        return UICommand.builder()
                .type(UICommandType.DownloadFile)
                .data(new FileDownload(
                        export.fileName(),
                        "application/yaml",
                        Base64.getEncoder().encodeToString(
                                export.content().getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    /** The view title (see {@code PageMetadataExtractor.getTitle}: falls back to toString()). */
    @Override
    public String toString() {
        return name != null ? name : "Workflow definition";
    }

    private static StatusType statusType(WorkflowDefinitionStatus status) {
        if (status == null) return StatusType.NONE;
        return switch (status) {
            case ACTIVE -> StatusType.SUCCESS;
            case DRAFT -> StatusType.WARNING;
            case DISABLED -> StatusType.DANGER;
            default -> StatusType.NONE;
        };
    }
}
