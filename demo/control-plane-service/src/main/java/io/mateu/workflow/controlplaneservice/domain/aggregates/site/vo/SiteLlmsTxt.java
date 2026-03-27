package io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo;


public record SiteLlmsTxt(String markdown) {

    public SiteLlmsTxt {
        if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("markdown is required");
    }
}
