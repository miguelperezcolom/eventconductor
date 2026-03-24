package io.mateu.workflow.contentservice.infra.out.persistence;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.contentservice.application.query.ContentQueryService;
import io.mateu.workflow.contentservice.application.query.dto.ContentDto;
import io.mateu.workflow.contentservice.application.query.dto.ContentRow;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;


@Service
@RequiredArgsConstructor
public class ContentDBQueryService implements ContentQueryService {

final ContentEntityRepository repository;

private ContentRow toDomain(ContentEntity entity) {
return new ContentRow(
entity.id.toString(),
entity.name
);
}

@Override
public String getLabel(String id) {
return repository.findById(Long.valueOf(id)).map(ContentEntity::getName).orElse("Unknown");
}

@Override
public Optional<ContentDto> getById(String id) {
    return repository.findById(Long.valueOf(id)).map(this::toDto);
    }

    private ContentDto toDto(ContentEntity entity) {
    return new ContentDto(
    entity.id.toString(),
    entity.name
    );
    }

    @Override
    public ListingData<ContentRow> findAll(String searchText,
        Object filters, Pageable pageable) {
        var page = repository.findAllByNameContainingIgnoreCase(searchText, org.springframework.data.domain.Pageable
        .ofSize(pageable.size())
        .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
        page.getContent().stream().map(this::toDomain).toList()));
        }

        }