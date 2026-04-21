package io.mateu.workflow.controlplaneservice.application.usecases.tier.delete;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.application.out.TierRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteTierUseCase {

    final TierRepository repository;

    @Transactional
    public void handle(DeleteTierCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(TierId::new)
                .toList());
    }

}
