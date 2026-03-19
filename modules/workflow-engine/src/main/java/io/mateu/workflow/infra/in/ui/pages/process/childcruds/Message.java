package io.mateu.workflow.infra.in.ui.pages.process.childcruds;


import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;

import java.time.LocalDateTime;

public record Message(@HiddenInList String processId, @HiddenInList String id, LocalDateTime time, String message) implements Identifiable {
}
