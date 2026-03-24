package io.mateu.workflow.contentservice.infra.out.persistence;

import io.mateu.workflow.contentservice.application.out.ContentRepository;
import io.mateu.workflow.contentservice.domain.aggregates.content.Content;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class ContentDBRepository implements ContentRepository {

final ContentEntityRepository repository;

@Override
public Optional<Content> findById(ContentId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Content toDomain(ContentEntity entity) {
    return new Content(
    new ContentId(entity.id),
    new ContentName(entity.name)
    );
    }

    private ContentEntity toEntity(Content content) {
    return new ContentEntity(
content.getId() != null?Long.valueOf(content.getId().id()):null,
content.getName().name()
    );
    }

    @Override
    public ContentId save(Content content) {
    return new ContentId(repository.save(toEntity(content)).id);
    }

    @Override
    public void deleteAllById(List<ContentId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(ContentId::id).toList());
        }
        }
