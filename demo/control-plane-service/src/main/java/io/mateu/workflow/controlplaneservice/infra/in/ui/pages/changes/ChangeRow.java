package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;

public record ChangeRow(@Hidden String id, String page, String country, String language, Status status) implements Identifiable {
}
