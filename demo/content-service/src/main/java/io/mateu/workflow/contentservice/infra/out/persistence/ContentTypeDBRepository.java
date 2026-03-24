package io.mateu.workflow.contentservice.infra.out.persistence;

import io.mateu.workflow.contentservice.application.out.ContentTypeRepository;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.ContentType;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class ContentTypeDBRepository implements ContentTypeRepository {

final ContentTypeEntityRepository repository;

@Override
public Optional<ContentType> findById(ContentTypeId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private ContentType toDomain(ContentTypeEntity entity) {
    return new ContentType(
    new ContentTypeId(entity.id),
    new ContentTypeName(entity.name)
    );
    }

    private ContentTypeEntity toEntity(ContentType contenttype) {
    return new ContentTypeEntity(
contenttype.getId() != null?Long.valueOf(contenttype.getId().id()):null,
contenttype.getName().name()
    );
    }

    @Override
    public ContentTypeId save(ContentType contenttype) {
    return new ContentTypeId(repository.save(toEntity(contenttype)).id);
    }

    @Override
    public void deleteAllById(List<ContentTypeId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(ContentTypeId::id).toList());
        }
        }
