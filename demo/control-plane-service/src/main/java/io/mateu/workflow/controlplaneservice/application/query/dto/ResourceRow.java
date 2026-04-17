package io.mateu.workflow.controlplaneservice.application.query.dto;

import java.time.LocalDateTime;

public record ResourceRow(String id, String name, LocalDateTime lastUpdated, int statusCode, long size, long milliseconds) {
}
