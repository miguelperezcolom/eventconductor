package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.application.services.EventTriggerService.TriggerState;
import io.mateu.workflow.domain.aggregates.EventTriggerOrigin;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;

/**
 * What the Event triggers page can be narrowed by; the search box matches workflow, trigger,
 * destination, type, filter, key and source.
 *
 * @param workflowId which workflow they start
 * @param origin     where they are declared: DEFINITION, EVENTS_FILE or API (created here)
 * @param state      whether they fire
 */
public record EventTriggerFilters(
        @MainFilter @Label("Workflow")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String workflowId,
        @MainFilter @Label("Declared in") EventTriggerOrigin origin,
        @MainFilter @Label("Status") TriggerState state) {
}
