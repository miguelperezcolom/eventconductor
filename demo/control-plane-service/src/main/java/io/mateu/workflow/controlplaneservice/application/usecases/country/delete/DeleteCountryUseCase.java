package io.mateu.workflow.controlplaneservice.application.usecases.country.delete;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteCountryUseCase {

    final CountryRepository repository;

    @Transactional
    public void handle(DeleteCountryCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(CountryCode::new)
                .toList());
    }

}
