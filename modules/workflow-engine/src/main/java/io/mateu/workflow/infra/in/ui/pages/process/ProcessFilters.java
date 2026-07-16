package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MainFilter;

public record ProcessFilters(@MainFilter @Label("Only errors") Boolean onlyErrors) {
}
