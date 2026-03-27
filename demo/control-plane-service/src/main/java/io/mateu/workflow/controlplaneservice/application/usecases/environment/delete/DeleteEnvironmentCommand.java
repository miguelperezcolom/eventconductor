package io.mateu.workflow.controlplaneservice.application.usecases.environment.delete;

import java.util.List;

public record DeleteEnvironmentCommand(List<String> ids) {
}
