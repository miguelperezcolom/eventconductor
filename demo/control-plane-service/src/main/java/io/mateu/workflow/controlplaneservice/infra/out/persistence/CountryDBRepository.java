package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class CountryDBRepository implements CountryRepository {

final CountryEntityRepository repository;

@Override
public Optional<Country> findById(CountryId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Country toDomain(CountryEntity entity) {
    return new Country(
    new CountryId(entity.id),
    new CountryName(entity.name)
    );
    }

    private CountryEntity toEntity(Country country) {
    return new CountryEntity(
country.getId() != null?Long.valueOf(country.getId().id()):null,
country.getName().name()
    );
    }

    @Override
    public CountryId save(Country country) {
    return new CountryId(repository.save(toEntity(country)).id);
    }

    @Override
    public void deleteAllById(List<CountryId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(CountryId::id).toList());
        }
        }
