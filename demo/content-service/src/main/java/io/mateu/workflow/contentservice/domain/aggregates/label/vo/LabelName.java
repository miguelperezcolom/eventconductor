package io.mateu.workflow.contentservice.domain.aggregates.label.vo;


public record LabelName(String name) {

public LabelName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
