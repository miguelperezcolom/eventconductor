package io.mateu.workflow.controlplaneservice.application.usecases.changes.createrelease;

public record AskForReleaseCreationCommand(String businessKey, String name, String siteId, String userId) {
}
