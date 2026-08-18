package io.mateu.testworker.infra.out.persistence;

import io.mateu.workflow.dtos.Variable;
import io.mateu.testworker.application.TaskOverrideStore;
import io.mateu.testworker.domain.LogLine;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.TaskOverride;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Overrides in the database, so they survive a restart and a redeploy. */
@Service
@ConditionalOnProperty(name = "worker.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaTaskOverrideStore implements TaskOverrideStore {

    private final TaskOverrideEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<TaskOverride> enabled() {
        return repository.findByEnabledTrue().stream().map(this::map).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskOverride> findById(String id) {
        return repository.findById(id).map(this::map);
    }

    @Override
    @Transactional
    public String save(TaskOverride row) {
        var id = row.id() == null || row.id().isBlank() ? UUID.randomUUID().toString() : row.id();
        var entity = repository.findById(id).orElseGet(TaskOverrideEntity::new);
        entity.setId(id);
        entity.setName(row.name());
        entity.setWorkflowDefinitionId(row.workflowDefinitionId());
        entity.setStepId(row.stepId());
        entity.setTaskId(row.taskId());
        entity.setEnabled(row.enabled());
        entity.setDurationMs(row.durationMs());
        entity.setOutcome(Json.nameOf(row.outcome()));
        entity.setReason(row.reason());
        entity.setFailuresBeforeSuccess(row.failuresBeforeSuccess());
        entity.setReplyTimes(row.replyTimes());
        entity.setIgnoreCancellation(row.ignoreCancellation());
        entity.setVariablesJson(Json.toJson(row.variables()));
        entity.setLogsJson(Json.toJson(row.logs()));
        repository.save(entity);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskOverride> findAll() {
        return repository.findAll().stream()
                .map(this::map)
                .sorted(Comparator.comparing(String::valueOf, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional
    public void deleteAllById(List<String> ids) {
        repository.deleteAllById(ids);
    }

    private TaskOverride map(TaskOverrideEntity entity) {
        return new TaskOverride(
                entity.getId(), entity.getName(), entity.getWorkflowDefinitionId(),
                entity.getStepId(), entity.getTaskId(), entity.isEnabled(),
                entity.getDurationMs(), Json.enumOf(Outcome.class, entity.getOutcome()),
                entity.getReason(), entity.getFailuresBeforeSuccess(), entity.getReplyTimes(),
                entity.isIgnoreCancellation(),
                Json.listFrom(entity.getVariablesJson(), Variable.class),
                Json.listFrom(entity.getLogsJson(), LogLine.class));
    }
}
