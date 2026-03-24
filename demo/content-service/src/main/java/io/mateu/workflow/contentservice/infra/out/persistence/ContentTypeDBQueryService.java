package io.mateu.workflow.contentservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.contentservice.application.query.ContentTypeQueryService;
import io.mateu.workflow.contentservice.application.query.dto.ContentTypeDto;
import io.mateu.workflow.contentservice.application.query.dto.ContentTypeRow;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class ContentTypeDBQueryService implements ContentTypeQueryService {

final ContentTypeEntityRepository repository;

private ContentTypeRow toDomain(ContentTypeEntity entity) {
return new ContentTypeRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(ContentTypeEntity::getName).orElse("Unknown");
}

@Override
public Optional<ContentTypeDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private ContentTypeDto toDto(ContentTypeEntity entity) {
    return new ContentTypeDto(
    entity.id.toString(),
    entity.name
    );
    }

    @Override
    public ListingData<ContentTypeRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }