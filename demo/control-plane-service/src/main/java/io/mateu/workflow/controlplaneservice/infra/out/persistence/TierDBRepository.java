package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.TierRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.Tier;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierParallelThreads;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TierDBRepository implements TierRepository {

    final TierEntityRepository repository;

    @Override
    public Optional<Tier> findById(TierId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Tier toDomain(TierEntity entity) {
        return new Tier(
                new TierId(entity.id),
                new TierName(entity.name),
                new TierParallelThreads(entity.parallelThreads)
        );
    }

    private TierEntity toEntity(Tier tier) {
        return new TierEntity(
                tier.getId().id(),
                tier.getName().name(),
                tier.getParallelThreads().parallelThreads()
        );
    }

    @Override
    public TierId save(Tier tier) {
        return new TierId(repository.save(toEntity(tier)).id);
    }

    @Override
    public void deleteAllById(List<TierId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(TierId::id).toList());
    }

    @Override
    public List<Tier> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }
}
