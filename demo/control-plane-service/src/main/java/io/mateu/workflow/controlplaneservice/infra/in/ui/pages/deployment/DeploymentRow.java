package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment;

import io.mateu.uidl.interfaces.Identifiable;

public record DeploymentRow(String id, String site, String country, String release) implements Identifiable {
}
