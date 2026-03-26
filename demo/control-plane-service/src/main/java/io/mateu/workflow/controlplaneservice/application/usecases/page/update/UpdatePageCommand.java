package io.mateu.workflow.controlplaneservice.application.usecases.page.update;

public record UpdatePageCommand(String id, String siteId, String name, String path) {
}
