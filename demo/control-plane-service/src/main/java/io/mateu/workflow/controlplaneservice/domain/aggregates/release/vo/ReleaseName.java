package io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo;


public record ReleaseName(String name) {

public ReleaseName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
