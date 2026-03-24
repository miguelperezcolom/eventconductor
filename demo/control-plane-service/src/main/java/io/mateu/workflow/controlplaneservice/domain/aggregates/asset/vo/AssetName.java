package io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo;


public record AssetName(String name) {

public AssetName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
