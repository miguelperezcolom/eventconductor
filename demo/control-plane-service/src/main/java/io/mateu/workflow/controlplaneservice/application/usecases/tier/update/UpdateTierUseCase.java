package io.mateu.workflow.controlplaneservice.application.usecases.tier.update;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.application.out.TierRepository;
import io.mateu.workflow.controlplaneservice.application.usecases.country.update.UpdateCountryCommand;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierParallelThreads;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateTierUseCase {

    final TierRepository repository;

    @Transactional
    public void handle(UpdateTierCommand command) {
        var tier = repository.findById(new TierId(command.id())).orElseThrow();
        tier.update(new TierName(command.name()), new TierParallelThreads(command.parallelThreads()));
        repository.save(tier);
    }

}