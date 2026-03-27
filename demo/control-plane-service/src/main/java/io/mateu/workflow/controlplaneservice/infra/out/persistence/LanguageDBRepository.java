package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.Language;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LanguageDBRepository implements LanguageRepository {

    final LanguageEntityRepository repository;

    @Override
    public Optional<Language> findById(LanguageCode id) {
        return repository.findById(id.code()).map(this::toDomain);
    }

    private Language toDomain(LanguageEntity entity) {
        return new Language(
                new LanguageCode(entity.code),
                new LanguageName(entity.name)
        );
    }

    private LanguageEntity toEntity(Language language) {
        return new LanguageEntity(
                language.getCode().code(),
                language.getName().name()
        );
    }

    @Override
    public LanguageCode save(Language language) {
        return new LanguageCode(repository.save(toEntity(language)).code);
    }

    @Override
    public void deleteAllById(List<LanguageCode> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(LanguageCode::code).toList());
    }
}
