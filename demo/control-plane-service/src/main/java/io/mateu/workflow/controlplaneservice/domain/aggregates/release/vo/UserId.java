package io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo;


public record UserId(String name) {

public UserId {
if (name == null || name.isBlank()) throw new IllegalArgumentException("user is required");
}
}
