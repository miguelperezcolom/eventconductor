package io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo;


public record EnvironmentName(String name) {

public EnvironmentName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("path is required");
}
}
