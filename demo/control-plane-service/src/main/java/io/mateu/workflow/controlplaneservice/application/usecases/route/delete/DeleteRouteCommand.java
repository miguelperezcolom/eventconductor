package io.mateu.workflow.controlplaneservice.application.usecases.route.delete;

import java.util.List;

public record DeleteRouteCommand(List<String> ids) {
    }
