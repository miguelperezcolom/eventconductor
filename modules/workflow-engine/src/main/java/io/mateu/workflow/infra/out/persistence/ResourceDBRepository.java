package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ResourceRepository;
import io.mateu.workflow.domain.aggregates.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ResourceDBRepository implements ResourceRepository {

    final StreamBridge streamBridge;
    final ResourceEntityRepository resourceEntityRepository;

    @Override
    public Optional<Resource> findById(String id) {
        return resourceEntityRepository.findById(id)
                .map(this::map);
    }

    private Resource map(ResourceEntity entity) {
        return new Resource(
                entity.getId(),
                entity.getTimestamp(),
                entity.getProcessId(),
                entity.getStepExecutionId(),
                entity.getType(),
                entity.getName(),
                entity.getUrl()
        );
    }

    @Override
    public String save(Resource message) {
        resourceEntityRepository.save(new ResourceEntity(
                message.getId(),
                message.getTimestamp(),
                message.getProcessId(),
                message.getStepExecutionId(),
                message.getType(),
                message.getName(),
                message.getUrl()
        ));
        return message.getId();
    }

    @Override
    public List<Resource> findAll() {
        return resourceEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        resourceEntityRepository.deleteAllById(selectedIds);
    }
}
