package io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo;


public record CountryName(String name) {

public CountryName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("path is required");
}
}
