package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;

/** What the Received events page can be narrowed by; the search box matches type, ids, source, receiver and keys. */
public record ReceivedEventFilters(
        @MainFilter @Label("Workflow")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String workflowId,
        @MainFilter @Label("Outcome") EventReceipt.Outcome outcome,
        @MainFilter @Label("Flow-control key") String flowKey) {
}
