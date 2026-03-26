package io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo;


public record PageName(String name) {

public PageName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
