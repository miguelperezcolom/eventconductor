package io.mateu.workflow.controlplaneservice.application.query.dto;

import java.time.LocalDateTime;

public record ResourceDto(
        String id,
        String name,
        String content,
        int statusCode,
        LocalDateTime lastUpdated,
        long size,
        long milliseconds
        ) {
}
