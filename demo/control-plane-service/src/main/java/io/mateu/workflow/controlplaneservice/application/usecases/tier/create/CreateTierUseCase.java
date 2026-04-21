package io.mateu.workflow.controlplaneservice.application.usecases.tier.create;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.application.out.TierRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.Tier;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierParallelThreads;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateTierUseCase {

    final TierRepository repository;

    @Transactional
    public String handle(CreateTierCommand command) {
        return repository.save(Tier.of(new TierId(command.id()), new TierName(command.name()), new TierParallelThreads(command.parallelThreads()))
        ).id();
    }

}
