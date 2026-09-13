package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * What the process listing can be narrowed by.
 *
 * <p>All of it is applied in the store — see {@code ProcessListingFilter}. Nothing here narrows a
 * page after loading it: on a deployment holding hundreds of thousands of processes that is the
 * same as not narrowing at all.
 *
 * <p>{@code onlyErrors} stays alongside {@code status} rather than being folded into it. It is one
 * click on the thing an operator reaches for most, and it was here first — removing it would break
 * every bookmark and every habit to save one redundant field.
 *
 * @param onlyErrors           the pre-existing toggle
 * @param workflowDefinitionId which definition, chosen from the ones actually loaded
 * @param status               one or more statuses (multi-select); a process matches if its status is any of them
 * @param businessKey          the business identifier, matched as a case-insensitive substring
 * @param createdFrom          inclusive; either end may be left open
 * @param createdTo            inclusive
 */
public record ProcessFilters(
        @MainFilter @Label("Only errors") Boolean onlyErrors,
        @MainFilter @Label("Definition")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class,
                label = WorkflowDefinitionIdLabelSupplier.class)
        String workflowDefinitionId,
        @MainFilter @Label("Status") Set<ProcessStatus> status,
        @MainFilter @Label("Business key")
        @Help("Matches the business key alone — an order number, a booking reference — as a "
                + "case-insensitive substring. The search box matches the name or the key; this "
                + "narrows to the key.")
        String businessKey,
        @Label("Created from") LocalDateTime createdFrom,
        @Label("Created to")
        @Help("Both ends are inclusive, and either may be left empty.")
        LocalDateTime createdTo) {
}
