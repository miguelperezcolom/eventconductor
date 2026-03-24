package io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo;


public record AssetVersionName(String name) {

public AssetVersionName {
if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
}
}
