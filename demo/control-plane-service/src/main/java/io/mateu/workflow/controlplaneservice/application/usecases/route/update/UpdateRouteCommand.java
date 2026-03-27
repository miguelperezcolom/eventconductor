package io.mateu.workflow.controlplaneservice.application.usecases.route.update;

public record UpdateRouteCommand(String id, String name, String languageCode, String countryCode, Long pageId, String path, String url) {
}
