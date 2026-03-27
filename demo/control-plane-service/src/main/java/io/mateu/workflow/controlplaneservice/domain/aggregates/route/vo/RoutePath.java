package io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo;


public record RoutePath(String path) {

public RoutePath {
if (path == null || path.isBlank()) throw new IllegalArgumentException("url is required");
}
}
