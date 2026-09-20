package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MainFilter;

/** Filter for the Tasks view: narrow to a single group. */
public record TaskContractFilters(@MainFilter @Label("Group") String group) {
}
