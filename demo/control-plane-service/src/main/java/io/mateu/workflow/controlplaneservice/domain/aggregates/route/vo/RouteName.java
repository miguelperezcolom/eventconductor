package io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo;


public record RouteName(String name) {

public RouteName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
