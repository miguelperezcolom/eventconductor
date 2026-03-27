package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.SiteQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.SiteRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class SiteDBQueryService implements SiteQueryService {

    final SiteEntityRepository repository;

    private SiteRow toDomain(SiteEntity entity) {
        return new SiteRow(
                entity.id.toString(),
                entity.name
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(id).map(SiteEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<SiteDto> getById(String id) {
        return repository.findById(id).map(this::toDto);
    }

    private SiteDto toDto(SiteEntity entity) {
        return new SiteDto(
                entity.id,
                entity.name,
                entity.url
        );
    }

    @Override
    public ListingData<SiteRow> findAll(String searchText,
                                        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}