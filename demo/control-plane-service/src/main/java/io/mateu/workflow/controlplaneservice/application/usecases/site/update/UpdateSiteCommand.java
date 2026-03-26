package io.mateu.workflow.controlplaneservice.application.usecases.site.update;

public record UpdateSiteCommand(String id, String name, String url) {
}
