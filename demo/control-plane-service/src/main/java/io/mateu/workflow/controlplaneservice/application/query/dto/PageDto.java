package io.mateu.workflow.controlplaneservice.application.query.dto;

import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageChangeFrequency;

import java.time.LocalDateTime;

public record PageDto(String id,
                      String siteId,
                      String name,
                      String path,
                      String jsonLd,
                      boolean dependsOnLanguage,
                      boolean dependsOnCountry,
                      PageChangeFrequency changeFrequency,
                      double priority,
                      LocalDateTime lastModification) {
}
