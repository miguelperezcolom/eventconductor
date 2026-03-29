package io.mateu.workflow.controlplaneservice.application.query.dto;

public record DeploymentDto(String id, String route, String country, Long releaseId) {
}
