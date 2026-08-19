package io.mateu.testworker.infra.out.persistence;

import io.mateu.workflow.dtos.Variable;
import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.ScenarioSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Received tasks in the database — the history the UI browses and builds overrides from. */
@Service
@ConditionalOnProperty(name = "worker.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaReceivedTaskStore implements ReceivedTaskStore {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "receivedAt");

    private final ReceivedTaskEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public int previousDeliveriesOf(String taskExecutionId) {
        return repository.findById(taskExecutionId)
                .map(ReceivedTaskEntity::getAttempt)
                .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceivedTask> findById(String id) {
        return repository.findById(id).map(this::map);
    }

    @Override
    @Transactional
    public String save(ReceivedTask row) {
        var entity = repository.findById(row.id()).orElseGet(ReceivedTaskEntity::new);
        entity.setId(row.id());
        entity.setProcessId(row.processId());
        entity.setWorkflowDefinitionId(row.workflowDefinitionId());
        entity.setStepId(row.stepId());
        entity.setTaskId(row.taskId());
        entity.setReceivedAt(row.receivedAt());
        entity.setAttempt(row.attempt());
        entity.setSource(Json.nameOf(row.source()));
        entity.setMatchedBy(row.matchedBy());
        entity.setOutcome(Json.nameOf(row.outcome()));
        entity.setDurationMs(row.durationMs());
        entity.setRepliedAt(row.repliedAt());
        entity.setNote(row.note());
        entity.setRequestVariablesJson(Json.toJson(row.requestVariables()));
        entity.setScenarioJson(row.scenarioJson());
        repository.save(entity);
        return row.id();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceivedTask> findAll() {
        return repository.findAll(NEWEST_FIRST).stream().map(this::map).toList();
    }

    @Override
    @Transactional
    public void deleteAllById(List<String> ids) {
        repository.deleteAllById(ids);
    }

    private ReceivedTask map(ReceivedTaskEntity entity) {
        return new ReceivedTask(
                entity.getId(), entity.getProcessId(), entity.getWorkflowDefinitionId(),
                entity.getStepId(), entity.getTaskId(), entity.getReceivedAt(),
                entity.getAttempt(), Json.enumOf(ScenarioSource.class, entity.getSource()),
                entity.getMatchedBy(), Json.enumOf(Outcome.class, entity.getOutcome()),
                entity.getDurationMs(), entity.getRepliedAt(), entity.getNote(),
                Json.listFrom(entity.getRequestVariablesJson(), Variable.class),
                entity.getScenarioJson());
    }
}
