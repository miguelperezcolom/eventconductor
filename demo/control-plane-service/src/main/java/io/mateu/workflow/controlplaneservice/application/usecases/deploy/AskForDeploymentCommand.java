package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import java.util.List;

public record AskForDeploymentCommand(String businessKey, List<String> routeIds, String releaseId) {
}
