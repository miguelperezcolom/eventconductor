package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.language.Language;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;

public interface LanguageRepository extends Repository<Language, LanguageCode> {
}
