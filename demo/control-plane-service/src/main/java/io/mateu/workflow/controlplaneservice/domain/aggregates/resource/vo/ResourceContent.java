package io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo;


public record ResourceContent(byte[] bytes) {

public ResourceContent {
if (bytes == null) throw new IllegalArgumentException("content is required");
}
}
