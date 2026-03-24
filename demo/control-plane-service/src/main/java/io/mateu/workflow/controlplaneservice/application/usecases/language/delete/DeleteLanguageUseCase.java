package io.mateu.workflow.controlplaneservice.application.usecases.language.delete;

import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteLanguageUseCase {

final LanguageRepository repository;

@Transactional
public void handle(DeleteLanguageCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(LanguageId::new)
.toList());
}

}
