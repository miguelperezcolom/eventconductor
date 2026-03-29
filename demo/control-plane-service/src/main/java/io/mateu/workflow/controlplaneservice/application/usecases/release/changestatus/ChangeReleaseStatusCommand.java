package io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus;

import java.util.List;

public record ChangeReleaseStatusCommand(List<String> ids, String status) {
}
