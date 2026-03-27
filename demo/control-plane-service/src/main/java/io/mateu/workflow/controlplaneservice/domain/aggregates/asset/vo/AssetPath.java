package io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo;


public record AssetPath(String path) {

    public AssetPath {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
    }
}
