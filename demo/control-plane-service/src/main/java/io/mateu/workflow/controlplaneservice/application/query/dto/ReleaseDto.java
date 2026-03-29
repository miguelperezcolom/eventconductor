package io.mateu.workflow.controlplaneservice.application.query.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReleaseDto(String id, String name,
                         String user,
                         LocalDateTime date,
                         String environment,
                         String site) {
}
