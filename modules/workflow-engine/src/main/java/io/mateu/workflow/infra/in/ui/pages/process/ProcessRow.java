package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;

import java.time.LocalDateTime;

public record ProcessRow(String id, String name, Status status, String created, String started, String finished) implements Identifiable {
}
