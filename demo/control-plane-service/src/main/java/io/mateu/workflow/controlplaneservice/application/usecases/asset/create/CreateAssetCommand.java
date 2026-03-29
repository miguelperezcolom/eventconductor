package io.mateu.workflow.controlplaneservice.application.usecases.asset.create;

public record CreateAssetCommand(String name, String path, String url, String countryCode) {
}
