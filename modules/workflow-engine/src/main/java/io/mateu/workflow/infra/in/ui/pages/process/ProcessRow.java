package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.data.Status;

public record ProcessRow(String id, String name, Status status, double percentage) {
}
