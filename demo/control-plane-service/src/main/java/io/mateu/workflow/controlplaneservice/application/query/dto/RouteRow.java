package io.mateu.workflow.controlplaneservice.application.query.dto;

import io.mateu.uidl.data.ColumnAction;
import io.mateu.uidl.fluent.Action;

public record RouteRow(String id, String name, String deployedHash, String hash, ColumnAction simulate) {
}
