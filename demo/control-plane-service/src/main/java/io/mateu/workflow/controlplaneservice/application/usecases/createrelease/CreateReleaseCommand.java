package io.mateu.workflow.controlplaneservice.application.usecases.createrelease;

public record CreateReleaseCommand(String name, String siteId, String userId) {
}
