package io.mateu.workflow.infra.in.ui.pages.process.childcruds;


import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;

public record Message(@HiddenInList String processId, @HiddenInList String id, String message) implements Identifiable {
}
