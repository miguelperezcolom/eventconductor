package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.CountryQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class CountryDBQueryService implements CountryQueryService {

final CountryEntityRepository repository;

private CountryRow toDomain(CountryEntity entity) {
return new CountryRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(CountryEntity::getName).orElse("Unknown");
}

@Override
public Optional<CountryDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private CountryDto toDto(CountryEntity entity) {
    return new CountryDto(
    entity.id.toString(),
    entity.name
    );
    }

    @Override
    public ListingData<CountryRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }