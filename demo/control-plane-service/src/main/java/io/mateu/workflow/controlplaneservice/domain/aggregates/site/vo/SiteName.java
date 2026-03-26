package io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo;


public record SiteName(String name) {

public SiteName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("path is required");
}
}
