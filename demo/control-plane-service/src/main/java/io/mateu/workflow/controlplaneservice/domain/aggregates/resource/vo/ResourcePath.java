package io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo;


public record ResourcePath(String path) {

public ResourcePath {
if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
}
}
