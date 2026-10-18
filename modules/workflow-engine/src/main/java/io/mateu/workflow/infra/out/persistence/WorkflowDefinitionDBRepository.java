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

    // Optional and resolved when used: the trigger stores do not depend on the definitions, but a
    // repository that cannot be built without them would make every test harness wire them too.
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.EventTriggerRepository> eventTriggers;
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.TriggerPauseRepository> triggerPauses;
    // Programmatic, not @Transactional: an annotation would make this bean a CGLIB proxy, and the
    // admin UI's view models hold it as a field that Mateu serializes — a proxy serializes into a
    // self-referencing cycle and every process detail page fails.
    private org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.support.TransactionTemplate> transactions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactions(org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.support.TransactionTemplate> transactions) {
        this.transactions = transactions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTriggerStores(
            org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.EventTriggerRepository> eventTriggers,
            org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.TriggerPauseRepository> triggerPauses) {
        this.eventTriggers = eventTriggers;
        this.triggerPauses = triggerPauses;
    }

    /** See {@link io.mateu.workflow.application.services.DefinitionTriggerCleanup}. */
    private io.mateu.workflow.application.services.DefinitionTriggerCleanup triggerCleanup() {
        return new io.mateu.workflow.application.services.DefinitionTriggerCleanup(
                eventTriggers == null ? null : eventTriggers.getIfAvailable(),
                triggerPauses == null ? null : triggerPauses.getIfAvailable());
    }

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
                .withLayout(layoutFromJson(entity.getLayoutJson()))
                .withProcessLock(processLockFromJson(entity.getProcessLockJson()))
                .withSyncInvocation(syncInvocationFromJson(entity.getSyncInvocationJson()))
                .withSchedules(schedulesFromJson(entity.getSchedulesJson()))
                .withTriggers(triggersFromJson(entity.getTriggersJson()))
                .withTags(tagsFromJson(entity.getTagsJson()))
                .withRetention(retentionFromJson(entity.getRetentionJson()))
                .withSearchAttributes(searchAttributesFromJson(entity.getSearchAttributesJson()));
    }

    /** The stored process-level lock, or null when the definition declares none (read leniently). */
    private static ProcessLock processLockFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, ProcessLock.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored synchronous-invocation configuration, or null when there is none (read leniently). */
    private static io.mateu.workflow.domain.aggregates.SyncInvocation syncInvocationFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, io.mateu.workflow.domain.aggregates.SyncInvocation.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored scheduled starts, or null when there are none (read leniently, like the lock). */
    private static List<CronSchedule> schedulesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, new TypeReference<List<CronSchedule>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored event triggers, or null when there are none (read leniently, like the schedules). */
    private static List<String> tagsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static List<io.mateu.workflow.domain.aggregates.EventTrigger> triggersFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, new TypeReference<List<io.mateu.workflow.domain.aggregates.EventTrigger>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored retention, or null when there is none (read leniently, like the tags). */
    static io.mateu.workflow.domain.aggregates.ProcessRetention retentionFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return io.mateu.workflow.domain.aggregates.ProcessRetention.fromJson(LAYOUT_MAPPER.readValue(json, Object.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored search attributes as the file wrote them, or null when there are none (read leniently). */
    @SuppressWarnings("unchecked")
    static java.util.Map<String, Object> searchAttributesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.readValue(json, java.util.LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    static String searchAttributesToJson(java.util.Map<String, Object> searchAttributes) {
        if (searchAttributes == null) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.writeValueAsString(searchAttributes);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the search attributes " + searchAttributes, e);
        }
    }

    static String retentionToJson(io.mateu.workflow.domain.aggregates.ProcessRetention retention) {
        if (retention == null) {
            return null;
        }
        try {
            return LAYOUT_MAPPER.writeValueAsString(retention.toJson());
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the retention " + retention, e);
        }
    }

    private static String processLockToJson(ProcessLock processLock) {
        return processLock == null ? null : toJson(processLock);
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
                layoutToJson(workflowDefinition.layout()),
                processLockToJson(workflowDefinition.processLock()),
                workflowDefinition.syncInvocation() == null ? null : toJson(workflowDefinition.syncInvocation()),
                workflowDefinition.schedules() == null ? null : toJson(workflowDefinition.schedules()),
                workflowDefinition.triggers() == null ? null : toJson(workflowDefinition.triggers()),
                workflowDefinition.tags() == null ? null : toJson(workflowDefinition.tags()),
                retentionToJson(workflowDefinition.retention()),
                searchAttributesToJson(workflowDefinition.searchAttributes())
        ));
        triggerCleanup().definitionSaved(workflowDefinition);
        return workflowDefinition.id();
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        return workflowDefinitionEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        var tx = transactions == null ? null : transactions.getIfAvailable();
        if (tx == null) {
            doDeleteAllById(selectedIds);
            return;
        }
        tx.executeWithoutResult(status -> doDeleteAllById(selectedIds));
    }

    /** The definitions and their UI/API triggers and pauses go together, or not at all. */
    private void doDeleteAllById(List<String> selectedIds) {
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
        triggerCleanup().definitionsDeleted(selectedIds);
    }
}
