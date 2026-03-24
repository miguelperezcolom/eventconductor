package io.mateu.workflow.controlplaneservice.application.usecases.language.update;

import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateLanguageUseCase {

final LanguageRepository repository;

@Transactional
public void handle(UpdateLanguageCommand command) {
var language = repository.findById(new LanguageId(Long.valueOf(command.id()))).orElseThrow();
language.update(new LanguageName(command.name()));
repository.save(language);
}

}
