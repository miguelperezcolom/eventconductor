package io.mateu.workflow.contentservice.domain.aggregates.content.vo;


public record ContentValue(LanguageCode languageCode, CountryCode countryCode, String value) {

public ContentValue {
if (languageCode == null) throw new IllegalArgumentException("language is required");
    if (countryCode == null) throw new IllegalArgumentException("country is required");
    if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
}
}
