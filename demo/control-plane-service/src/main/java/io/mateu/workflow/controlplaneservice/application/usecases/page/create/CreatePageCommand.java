package io.mateu.workflow.controlplaneservice.application.usecases.page.create;

public record CreatePageCommand(String siteId, String name, String path, String jsonLd, boolean dependsOnLanguage, boolean dependsOnCountry) {
}
