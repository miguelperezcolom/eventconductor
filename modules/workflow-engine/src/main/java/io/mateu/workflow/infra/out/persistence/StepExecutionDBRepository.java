package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.PENDING;
import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.RUNNING;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class StepExecutionDBRepository implements StepExecutionRepository {

    final StepExecutionEntityRepository stepExecutionEntityRepository;
    final OutboxMessageEntityRepository outboxMessageEntityRepository;

    @Override
    public Optional<StepExecution> findById(String id) {
        return stepExecutionEntityRepository.findById(id).map(this::map);
    }

    private StepExecution map(StepExecutionEntity entity) {
        return new StepExecution(
                entity.getId(),
                entity.getProcessId(),
                entity.getWorkflowDefinitionId(),
                entity.getStepId(),
                entity.getStepJson(),
                listFromJson(entity.getVariables(), Variable.class),
                StepExecutionStatus.valueOf(entity.getStatus()),
                entity.getWorkerId(),
                entity.getOrder(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getAttemptCount(),
                entity.getDeadlineAt()
        );
    }

    @Override
    public String save(StepExecution stepExecution) {
        stepExecutionEntityRepository.save(new StepExecutionEntity(
            stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getWorkflowDefinitionId(),
                stepExecution.getStepId(),
                stepExecution.getStepJson(),
                toJson(stepExecution.getVariables()),
                stepExecution.getStatus().name(),
                stepExecution.getWorkerId(),
                stepExecution.getOrder(),
                stepExecution.getStartedAt(),
                stepExecution.getFinishedAt(),
                stepExecution.getAttemptCount(),
                stepExecution.getDeadlineAt()
        ));

        stepExecution.popEvents().stream()
                .map(OutboxMessageEntity::new)
                .forEach(outboxMessageEntityRepository::save);

        return stepExecution.id();
    }

    @Override
    public List<StepExecution> findAll() {
        return stepExecutionEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        stepExecutionEntityRepository.deleteAllById(selectedIds);
    }

    @Override
    public List<StepExecution> findByProcess(Process process) {
        return stepExecutionEntityRepository.findAllByProcessIdOrderByOrder(process.id()).stream()
                .map(this::map).toList();
    }

    @Override
    public List<StepExecution> findPendingOrRunning() {
        return stepExecutionEntityRepository
                .findAllByStatusIn(List.of(PENDING.name(), RUNNING.name()))
                .stream().map(this::map).toList();
    }

    @Override
    public List<StepExecution> findPendingOrRunningByProcessId(String processId) {
        return stepExecutionEntityRepository
                .findAllByProcessIdAndStatusIn(processId, List.of(PENDING.name(), RUNNING.name()))
                .stream().map(this::map).toList();
    }

    @Override
    public List<StepExecution> findDue(LocalDateTime now) {
        return stepExecutionEntityRepository
                .findAllByStatusInAndDeadlineAtLessThanEqual(List.of(PENDING.name(), RUNNING.name()), now)
                .stream().map(this::map).toList();
    }

    @Override
    public List<StepExecution> findLiveWithoutDeadline() {
        return stepExecutionEntityRepository
                .findAllByStatusInAndStartedAtIsNotNullAndDeadlineAtIsNull(List.of(PENDING.name(), RUNNING.name()))
                .stream().map(this::map).toList();
    }
}
