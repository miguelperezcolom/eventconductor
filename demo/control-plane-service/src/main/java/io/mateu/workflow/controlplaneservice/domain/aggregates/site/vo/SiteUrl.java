package io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo;


public record SiteUrl(String url) {

public SiteUrl {
if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
}
}
