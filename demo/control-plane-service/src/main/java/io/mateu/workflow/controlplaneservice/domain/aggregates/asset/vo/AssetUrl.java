package io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo;


public record AssetUrl(String url) {

public AssetUrl {
if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
}
}
