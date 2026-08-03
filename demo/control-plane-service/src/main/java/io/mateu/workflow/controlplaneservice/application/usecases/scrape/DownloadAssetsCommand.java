package io.mateu.workflow.controlplaneservice.application.usecases.scrape;

public record DownloadAssetsCommand(String siteId, String taskExecutionId, String processId) {
}
