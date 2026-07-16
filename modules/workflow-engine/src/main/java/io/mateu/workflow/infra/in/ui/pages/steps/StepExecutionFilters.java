package io.mateu.workflow.infra.in.ui.pages.steps;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MainFilter;

public record StepExecutionFilters(@MainFilter @Label("Only errors") Boolean onlyErrors) {
}
