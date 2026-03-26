package io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo;


public record PagePath(String path) {

public PagePath {
if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
}
}
