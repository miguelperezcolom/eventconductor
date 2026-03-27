package io.mateu.workflow.controlplaneservice.application.usecases.route.create;

public record CreateRouteCommand(String name, String languageCode, String countryCode, Long pageId, String path,
                                 String url) {
}
