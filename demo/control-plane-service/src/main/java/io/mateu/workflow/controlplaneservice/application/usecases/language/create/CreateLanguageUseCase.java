package io.mateu.workflow.controlplaneservice.application.usecases.language.create;

import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.Language;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateLanguageUseCase {

final LanguageRepository repository;

@Transactional
public String handle(CreateLanguageCommand command) {
return repository.save(Language.of(new LanguageName(command.name()))
).id().toString();
}

}
