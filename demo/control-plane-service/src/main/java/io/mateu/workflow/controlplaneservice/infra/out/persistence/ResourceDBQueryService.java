package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.ResourceQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class ResourceDBQueryService implements ResourceQueryService {

    final ResourceEntityRepository repository;

    private ResourceRow toDomain(ResourceEntity entity) {
        return new ResourceRow(
                entity.id,
                entity.name,
                entity.lastUpdated,
                entity.statusCode,
                entity.size,
                entity.milliseconds
        );
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(id).map(ResourceEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<ResourceDto> getById(String id) {
        return repository.findById(id).map(this::toDto);
    }

    private ResourceDto toDto(ResourceEntity entity) {
        return new ResourceDto(
                entity.id.toString(),
                entity.name,
                entity.content != null?new String(entity.content):null,
                entity.statusCode,
                entity.lastUpdated,
                entity.size,
                entity.milliseconds
        );
    }

    @Override
    public ListingData<ResourceRow> findAll(String searchText,
                                            Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}