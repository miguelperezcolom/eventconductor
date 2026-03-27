package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.RouteQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class RouteDBQueryService implements RouteQueryService {

    final RouteEntityRepository repository;

    private RouteRow toDomain(RouteEntity entity) {
        return new RouteRow(
                entity.id.toString(),
                entity.name
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(Long.valueOf(id)).map(RouteEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<RouteDto> getById(String id) {
        return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private RouteDto toDto(RouteEntity entity) {
        return new RouteDto(
                entity.id.toString(),
                entity.name,
                entity.languageCode,
                entity.countryCode,
                entity.pageId,
                entity.path,
                entity.url
        );
    }

    @Override
    public ListingData<RouteRow> findAll(String searchText,
                                         Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}