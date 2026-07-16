package io.mateu.workflow.infra.in.ui.pages.steps;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;

public record StepExecutionRow(String id, String processId, String stepId, Status status, String startedAt, int attempts) implements Identifiable {
}
