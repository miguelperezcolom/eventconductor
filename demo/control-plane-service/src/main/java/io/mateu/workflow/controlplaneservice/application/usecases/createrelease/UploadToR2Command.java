package io.mateu.workflow.controlplaneservice.application.usecases.createrelease;

public record UploadToR2Command(String name, String siteId, String userId, String taskExecutionId, String releaseId) {
}
