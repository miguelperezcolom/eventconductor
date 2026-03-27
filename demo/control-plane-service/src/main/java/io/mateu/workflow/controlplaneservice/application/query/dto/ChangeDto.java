package io.mateu.workflow.controlplaneservice.application.query.dto;

public record ChangeDto(String pageId, String page, String country, String language, ChangeStatus status) {
}
