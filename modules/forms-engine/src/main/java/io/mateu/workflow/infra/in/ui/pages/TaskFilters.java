package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MainFilter;

/** What the Tasks inbox can be narrowed by. */
public record TaskFilters(
        @MainFilter @Label("Show") TaskScopeOption scope,
        @MainFilter @Label("Overdue only") boolean overdueOnly,
        @Label("Min priority") Integer minPriority,
        @Label("Process") String processId,
        @Label("Business key") String businessKey) {
}
