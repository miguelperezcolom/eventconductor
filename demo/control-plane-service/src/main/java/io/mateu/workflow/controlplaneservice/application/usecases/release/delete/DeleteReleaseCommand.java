package io.mateu.workflow.controlplaneservice.application.usecases.release.delete;

import java.util.List;

public record DeleteReleaseCommand(List<String> ids) {
    }
