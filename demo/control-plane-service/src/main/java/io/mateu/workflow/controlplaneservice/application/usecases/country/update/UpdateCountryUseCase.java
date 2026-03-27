package io.mateu.workflow.controlplaneservice.application.usecases.country.update;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateCountryUseCase {

    final CountryRepository repository;

    @Transactional
    public void handle(UpdateCountryCommand command) {
        var country = repository.findById(new CountryCode(command.code())).orElseThrow();
        country.update(new CountryName(command.name()));
        repository.save(country);
    }

}
