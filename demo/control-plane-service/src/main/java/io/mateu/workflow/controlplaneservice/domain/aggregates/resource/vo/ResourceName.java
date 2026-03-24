package io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo;


public record ResourceName(String name) {

public ResourceName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
