package io.mateu.workflow.controlplaneservice.application.usecases.country.create;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateCountryUseCase {

final CountryRepository repository;

@Transactional
public String handle(CreateCountryCommand command) {
return repository.save(Country.of(new CountryName(command.name()))
).id().toString();
}

}
