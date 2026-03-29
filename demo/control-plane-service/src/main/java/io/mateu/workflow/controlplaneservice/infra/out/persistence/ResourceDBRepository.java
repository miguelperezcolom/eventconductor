package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceContent;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourcePath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ResourceDBRepository implements ResourceRepository {

    final ResourceEntityRepository repository;

    @Override
    public Optional<Resource> findById(ResourceId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Resource toDomain(ResourceEntity entity) {
        return new Resource(
                new ResourceId(entity.id),
                new ResourceName(entity.name),
                new ResourcePath(entity.path),
                new ResourceContent(entity.content)
        );
    }

    private ResourceEntity toEntity(Resource resource) {
        return new ResourceEntity(
                resource.getId().id(),
                resource.getName().name(),
                resource.getPath().path(),
                resource.getContent().bytes()
        );
    }

    @Override
    public ResourceId save(Resource resource) {
        return new ResourceId(repository.save(toEntity(resource)).id);
    }

    @Override
    public void deleteAllById(List<ResourceId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(ResourceId::id).toList());
    }

}
