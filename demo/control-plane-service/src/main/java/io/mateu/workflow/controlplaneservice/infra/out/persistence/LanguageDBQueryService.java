package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.LanguageQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.LanguageDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.LanguageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class LanguageDBQueryService implements LanguageQueryService {

final LanguageEntityRepository repository;

private LanguageRow toDomain(LanguageEntity entity) {
return new LanguageRow(
entity.code,
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(id).map(LanguageEntity::getName).orElse("Unknown");
}

@Override
public Optional<LanguageDto> getById(String id) {
    return repository.findById(id).map(this::toDto);
    }

    private LanguageDto toDto(LanguageEntity entity) {
    return new LanguageDto(
    entity.code,
    entity.name
    );
    }

    @Override
    public ListingData<LanguageRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }