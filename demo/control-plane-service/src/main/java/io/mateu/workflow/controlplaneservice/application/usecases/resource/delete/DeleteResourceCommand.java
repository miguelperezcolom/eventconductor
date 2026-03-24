package io.mateu.workflow.controlplaneservice.application.usecases.resource.delete;

import java.util.List;

public record DeleteResourceCommand(List<String> ids) {
    }
