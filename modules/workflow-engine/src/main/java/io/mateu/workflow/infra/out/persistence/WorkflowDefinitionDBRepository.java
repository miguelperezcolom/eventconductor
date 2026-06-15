package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class WorkflowDefinitionDBRepository implements WorkflowDefinitionRepository {

    final WorkflowDefinitionEntityRepository workflowDefinitionEntityRepository;
    final WorkflowDefinitionValidator workflowDefinitionValidator;

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
                entity.getDraftOfId(),
                entity.isLimitConcurrentExecutions(),
                entity.getMaxConcurrentExecutions(),
                entity.isEnqueueOnLimit(),
                listFromJson(entity.getStepsJson(), Step.class)
        );
    }

    @Override
    public String save(WorkflowDefinition workflowDefinition) {
        workflowDefinitionValidator.validate(workflowDefinition);
        workflowDefinitionEntityRepository.save(new WorkflowDefinitionEntity(
                workflowDefinition.id(),
                workflowDefinition.name(),
                workflowDefinition.version(),
                workflowDefinition.description(),
                workflowDefinition.status().name(),
                workflowDefinition.draftOfId(),
                toJson(workflowDefinition.steps().stream().map(step -> step.withWorkflowDefinitionId(workflowDefinition.id())).toList()),
                workflowDefinition.limitConcurrentExecutions(),
                workflowDefinition.maxConcurrentExecutions(),
                workflowDefinition.enqueueOnLimit()
        ));
        return workflowDefinition.id();
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        return workflowDefinitionEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        selectedIds.stream().map(workflowDefinitionEntityRepository::findById)
                .map(Optional::orElseThrow)
                .filter(entity -> WorkflowDefinitionStatus.ACTIVE.name().equals(entity.status))
                .findAny()
                .ifPresent(entity -> {
                    throw new RuntimeException("Cannot delete active workflow definition (" + entity.getName() + ")");
                });
        workflowDefinitionEntityRepository.deleteAllById(selectedIds);
    }
}
