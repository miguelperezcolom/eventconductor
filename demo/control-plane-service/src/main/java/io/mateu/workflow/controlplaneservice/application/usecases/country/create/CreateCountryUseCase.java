package io.mateu.workflow.controlplaneservice.application.usecases.country.create;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateCountryUseCase {

    final CountryRepository repository;

    @Transactional
    public String handle(CreateCountryCommand command) {
        return repository.save(Country.of(new CountryCode(command.code()), new CountryName(command.name()), new TierId(command.tierId()))
        ).code();
    }

}
