package io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo;


public record PageJsonLd(String json) {

    public PageJsonLd {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("json is required");
    }
}
