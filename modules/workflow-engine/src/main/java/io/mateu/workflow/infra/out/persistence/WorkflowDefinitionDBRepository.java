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
    final io.mateu.workflow.application.services.WorkflowDefinitionVersioningService versioningService;

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
                entity.isLimitConcurrentExecutions(),
                entity.getMaxConcurrentExecutions(),
                entity.isEnqueueOnLimit(),
                entity.getCronExpression(),
                entity.getDefaultMaxStepExecutions(),
                listFromJson(entity.getStepsJson(), Step.class),
                entity.isPaused(),
                WorkflowStatus.of(entity.getDeclaredStatus(), false, false),
                WorkflowStatus.of(entity.getRuntimeStatus(), false, false)
        ).withMaxSteps(entity.getMaxSteps());
    }

    @Override
    public String save(WorkflowDefinition workflowDefinition) {
        workflowDefinitionValidator.validate(workflowDefinition);
        // Record a new immutable version iff the content changed, and stamp the head row with the
        // engine-assigned version (the author-supplied .ec version is not authoritative). Lifecycle
        // toggles and unchanged re-imports reconcile to the existing version and record nothing.
        int version = versioningService.reconcile(workflowDefinition);
        workflowDefinitionEntityRepository.save(new WorkflowDefinitionEntity(
                workflowDefinition.id(),
                workflowDefinition.name(),
                version,
                workflowDefinition.description(),
                toJson(workflowDefinition.steps().stream().map(step -> step.withWorkflowDefinitionId(workflowDefinition.id())).toList()),
                workflowDefinition.limitConcurrentExecutions(),
                workflowDefinition.maxConcurrentExecutions(),
                workflowDefinition.enqueueOnLimit(),
                workflowDefinition.cronExpression(),
                workflowDefinition.defaultMaxStepExecutions(),
                workflowDefinition.maxSteps(),
                workflowDefinition.paused(),
                workflowDefinition.declaredStatus().name(),
                workflowDefinition.runtimeStatus().name()
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
                .filter(entity -> WorkflowStatus.of(entity.getRuntimeStatus(), false, false)
                        .and(WorkflowStatus.of(entity.getDeclaredStatus(), false, false))
                        .accceptsNewInstances())
                .findAny()
                .ifPresent(entity -> {
                    throw new RuntimeException("Cannot delete an active workflow definition (disable or archive it first): " + entity.getName());
                });
        workflowDefinitionEntityRepository.deleteAllById(selectedIds);
    }
}
