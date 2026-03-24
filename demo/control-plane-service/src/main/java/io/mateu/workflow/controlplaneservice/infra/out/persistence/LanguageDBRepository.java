package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.Language;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class LanguageDBRepository implements LanguageRepository {

final LanguageEntityRepository repository;

@Override
public Optional<Language> findById(LanguageId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Language toDomain(LanguageEntity entity) {
    return new Language(
    new LanguageId(entity.id),
    new LanguageName(entity.name)
    );
    }

    private LanguageEntity toEntity(Language language) {
    return new LanguageEntity(
language.getId() != null?Long.valueOf(language.getId().id()):null,
language.getName().name()
    );
    }

    @Override
    public LanguageId save(Language language) {
    return new LanguageId(repository.save(toEntity(language)).id);
    }

    @Override
    public void deleteAllById(List<LanguageId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(LanguageId::id).toList());
        }
        }
