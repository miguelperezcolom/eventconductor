package io.mateu.workflow.infra.in.ui.pages.incidents;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;

/** What the Incidents page can be narrowed by; the search box matches the step and the error. */
public record IncidentFilters(
        @MainFilter @Label("Workflow")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String workflowId) {
}
