package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class WorkflowDefinitionDBRepository implements WorkflowDefinitionRepository {

    final StreamBridge streamBridge;
    final WorkflowDefinitionEntityRepository workflowDefinitionEntityRepository;

    @Override
    public Optional<WorkflowDefinition> findById(String id) {
        return workflowDefinitionEntityRepository.findById(id).map(this::map);
    }

    private WorkflowDefinition map(WorkflowDefinitionEntity entity) {
        return new WorkflowDefinition(
                entity.getId(),
                entity.getName(),
                entity.getVersion(),
                entity.getDescription(),
                WorkflowDefinitionStatus.valueOf(entity.getStatus()),
                listFromJson(entity.getStepsJson(), Step.class)
        );
    }

    @Override
    public String save(WorkflowDefinition workflowDefinition) {
        workflowDefinitionEntityRepository.save(new WorkflowDefinitionEntity(
                workflowDefinition.id(),
                workflowDefinition.name(),
                workflowDefinition.version(),
                workflowDefinition.description(),
                workflowDefinition.status().name(),
                toJson(workflowDefinition.steps())
        ));
        return workflowDefinition.id();
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        return workflowDefinitionEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        workflowDefinitionEntityRepository.deleteAllById(selectedIds);
    }
}
