package io.mateu.workflow.controlplaneservice.application.usecases.page.update;

public record UpdatePageCommand(String id, String siteId, String name, String path, String jsonLd, boolean dependsOnLanguage, boolean dependsOnCountry,
                                io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageChangeFrequency changeFrequency,
                                double priority,
                                java.util.List<io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page.PageCheckViewModel> checks) {
}
