package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;

public record DeploymentRow(String id, String route, String country, Status release) implements Identifiable {
}
