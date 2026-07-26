package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Zone;
import io.mateu.uidl.annotations.Zones;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

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
public class WorkflowDefinitionDetailView {

    /** Custom element that renders the workflow as an ELK graph. Shipped by this module. */
    private static final String GRAPH_TAG = "eventconductor-workflow-graph";
    /** Same-origin URL of the component's ESM bundle (served from META-INF/resources). */
    private static final String GRAPH_MODULE = "/eventconductor/workflow-graph.js";

    final WorkflowDefinitionRepository repository;

    @Hidden
    String id;

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
        this.steps = def.steps();
        // Rendered through mateu's Element/import mechanism: mateu dynamically imports the module the
        // first time the tag is used, and the custom element upgrades in place.
        this.workflow = new Element(
                GRAPH_TAG,
                Map.of(
                        "import", GRAPH_MODULE,
                        "value", toJson(def),
                        "readonly", "true"),
                "");
        return this;
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
