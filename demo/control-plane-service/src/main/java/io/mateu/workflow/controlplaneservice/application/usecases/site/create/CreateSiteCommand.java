package io.mateu.workflow.controlplaneservice.application.usecases.site.create;

public record CreateSiteCommand(String id, String name, String url, String llmsTxt) {
}
