package io.mateu.workflow.controlplaneservice.application.usecases.page.create;

import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page.PageCheckViewModel;

import java.util.List;

public record CreatePageCommand(String siteId,
                                String name,
                                String path,
                                String jsonLd,
                                boolean dependsOnLanguage,
                                boolean dependsOnCountry,
                                io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageChangeFrequency changeFrequency,
                                double priority, List<PageCheckViewModel> checks) {
}
