package io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo;


public record LanguageName(String name) {

public LanguageName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
