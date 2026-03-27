package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CountryDBRepository implements CountryRepository {

    final CountryEntityRepository repository;

    @Override
    public Optional<Country> findById(CountryCode id) {
        return repository.findById(id.code()).map(this::toDomain);
    }

    private Country toDomain(CountryEntity entity) {
        return new Country(
                new CountryCode(entity.code),
                new CountryName(entity.name)
        );
    }

    private CountryEntity toEntity(Country country) {
        return new CountryEntity(
                country.getCode().code(),
                country.getName().name()
        );
    }

    @Override
    public CountryCode save(Country country) {
        return new CountryCode(repository.save(toEntity(country)).code);
    }

    @Override
    public void deleteAllById(List<CountryCode> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(CountryCode::code).toList());
    }
}
