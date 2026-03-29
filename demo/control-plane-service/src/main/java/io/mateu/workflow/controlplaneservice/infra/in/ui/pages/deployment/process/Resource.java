package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process;


import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;

public record Resource(@HiddenInList String processId, @HiddenInList String id, String name, String link) implements Identifiable {
}
