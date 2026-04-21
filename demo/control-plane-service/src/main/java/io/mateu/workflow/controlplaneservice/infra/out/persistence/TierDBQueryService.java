package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.CountryQueryService;
import io.mateu.workflow.controlplaneservice.application.query.TierQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class TierDBQueryService implements TierQueryService {

    final TierEntityRepository repository;

    private TierRow toDomain(TierEntity entity) {
        return new TierRow(
                entity.id,
                entity.name,
                entity.parallelThreads
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(id).map(TierEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<TierDto> getById(String id) {
        return repository.findById(id).map(this::toDto);
    }

    private TierDto toDto(TierEntity entity) {
        return new TierDto(
                entity.id,
                entity.name,
                entity.parallelThreads
        );
    }

    @Override
    public ListingData<TierRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}