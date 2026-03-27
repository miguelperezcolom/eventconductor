package io.mateu.workflow.contentservice.domain.aggregates.content.vo;


public record ContentValue(String languageCode, String countryCode, String value) {

public ContentValue {
if (languageCode == null || languageCode.isBlank()) throw new IllegalArgumentException("language is required");
    if (countryCode == null || countryCode.isBlank()) throw new IllegalArgumentException("country is required");
    if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
}
}
