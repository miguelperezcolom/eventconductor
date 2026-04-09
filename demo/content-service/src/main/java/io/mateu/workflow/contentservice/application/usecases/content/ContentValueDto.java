package io.mateu.workflow.contentservice.application.usecases.content;

import io.mateu.workflow.contentservice.domain.aggregates.content.vo.CountryCode;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.LanguageCode;

public record ContentValueDto(CountryCode country, LanguageCode language, String value) {
}
