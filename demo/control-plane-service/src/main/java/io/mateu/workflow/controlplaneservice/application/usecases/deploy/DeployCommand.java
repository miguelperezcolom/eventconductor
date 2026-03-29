package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import java.util.List;

public record DeployCommand(List<String> routeIds, String releaseId) {
}
