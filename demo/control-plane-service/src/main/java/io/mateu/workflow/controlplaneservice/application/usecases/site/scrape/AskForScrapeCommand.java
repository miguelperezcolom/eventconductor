package io.mateu.workflow.controlplaneservice.application.usecases.site.scrape;

public record AskForScrapeCommand(String siteId, String processBusinessKey) {
}
