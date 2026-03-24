package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.controlplaneservice.application.query.ResourceQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceRow;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class ResourceDBQueryService implements ResourceQueryService {

final ResourceEntityRepository repository;

private ResourceRow toDomain(ResourceEntity entity) {
return new ResourceRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(ResourceEntity::getName).orElse("Unknown");
}

@Override
public Optional<ResourceDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private ResourceDto toDto(ResourceEntity entity) {
    return new ResourceDto(
    entity.id.toString(),
    entity.name
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