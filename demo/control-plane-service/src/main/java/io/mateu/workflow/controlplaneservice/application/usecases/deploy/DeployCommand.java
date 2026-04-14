package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import java.util.List;

public record DeployCommand(String taskExecutionId, List<String> routeIds, String releaseId, String deploymentId) {
}
