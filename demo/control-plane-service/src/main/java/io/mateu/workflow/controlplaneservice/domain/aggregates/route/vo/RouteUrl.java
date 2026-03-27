package io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo;


public record RouteUrl(String url) {

public RouteUrl {
if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
}
}
