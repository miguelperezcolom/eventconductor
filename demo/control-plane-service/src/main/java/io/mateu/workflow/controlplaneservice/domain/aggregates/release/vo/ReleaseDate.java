package io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo;


import java.time.LocalDateTime;

public record ReleaseDate(LocalDateTime dateTime) {

public ReleaseDate {
if (dateTime == null) throw new IllegalArgumentException("date is required");
}
}
