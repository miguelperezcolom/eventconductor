package io.mateu.workflow.controlplaneservice.application.usecases.scrape;

public record ScrapeCommand(String siteId, String taskExecutionId, String processId) {
}
