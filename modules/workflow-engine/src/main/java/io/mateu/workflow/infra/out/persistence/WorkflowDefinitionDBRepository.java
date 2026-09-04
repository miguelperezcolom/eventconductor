package io.mateu.workflow.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class WorkflowDefinitionDBRepository implements WorkflowDefinitionRepository {

    /** Its own mapper because {@code JsonSerializer} reads lists and pojos, not maps of records. */
    private static final ObjectMapper LAYOUT_MAPPER = new ObjectMapper();

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
        ).withMaxSteps(entity.getMaxSteps())
                .withLayout(layoutFromJson(entity.getLayoutJson()));
    }

    /**
     * The stored arrangement, or null when there is none.
     *
     * <p>Read leniently on purpose. A layout is presentation: a row whose JSON cannot be read is a
     * graph that lays itself out, not a definition the engine should refuse to load. Nothing here is
     * worth failing a definition over.
     */
    private static Map<String, NodePosition> layoutFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, new TypeReference<Map<String, NodePosition>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** The arrangement as the column holds it, or null when the definition has none. */
    private static String layoutToJson(Map<String, NodePosition> layout) {
        return layout == null ? null : toJson(layout);
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
                workflowDefinition.runtimeStatus().name(),
                layoutToJson(workflowDefinition.layout())
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
