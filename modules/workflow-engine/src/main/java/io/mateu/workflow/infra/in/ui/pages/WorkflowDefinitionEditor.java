package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.data.Element;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.toJson;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionEditor {

    /** Custom element that renders the workflow as an ELK graph. Shipped by this module. */
    private static final String GRAPH_TAG = "eventconductor-workflow-graph";
    /** Same-origin URL of the component's ESM bundle (served from META-INF/resources). */
    private static final String GRAPH_MODULE = "/eventconductor/workflow-graph.js";

    final WorkflowDefinitionRepository repository;

    String workflowId;

    Element workflow;


    public WorkflowDefinitionEditor load(String workflowId) {
        this.workflowId = workflowId;
        var def = repository.findById(workflowId).orElseThrow();
        // Rendered through mateu's Element/import mechanism: mateu dynamically imports the
        // module the first time the tag is used, and the custom element upgrades in place.
        workflow = new Element(
                GRAPH_TAG,
                Map.of(
                        "import", GRAPH_MODULE,
                        "value", toJson(def),
                        "readonly", "true"),
                "");
        return this;
    }

}
