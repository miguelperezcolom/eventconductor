package io.mateu.workflow.controlplaneservice.application.query.dto;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.data.ColumnAction;
import io.mateu.uidl.data.ColumnActionGroup;
import io.mateu.uidl.data.Status;

public record ReleaseRow(String id, String site, String name, String user, String date, Status status, ColumnActionGroup action) {
}
