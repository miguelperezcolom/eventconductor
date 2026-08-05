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

import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.AWAITING_RETRY;
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
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final io.mateu.workflow.infra.out.async.OutboxSignal outboxSignal;

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
                entity.getDeadlineAt(),
                entity.getAwaitingMessageName(),
                entity.getAwaitingCorrelationKey(),
                entity.getVersion()
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
                stepExecution.stepTypeName(),
                toJson(stepExecution.getVariables()),
                stepExecution.getStatus().name(),
                stepExecution.getWorkerId(),
                stepExecution.getOrder(),
                stepExecution.getStartedAt(),
                stepExecution.getFinishedAt(),
                stepExecution.getAttemptCount(),
                stepExecution.getDeadlineAt(),
                stepExecution.getAwaitingMessageName(),
                stepExecution.getAwaitingCorrelationKey(),
                stepExecution.getVersion()
        ));

        // Captured here, at the one moment the event and the context that produced it are both
        // in hand: the relay publishes this row later, from a thread that has neither.
        var traceParent = workflowTracing.currentTraceParent();
        var outbox = stepExecution.popEvents().stream()
                .map(event -> new OutboxMessageEntity(event, traceParent)).toList();
        outboxMessageEntityRepository.saveAll(outbox);
        if (!outbox.isEmpty()) {
            // Wake this pod's relay once the transaction commits, rather than leaving the row to
            // be found on the next poll — which is latency added to every step.
            outboxSignal.raise();
        }

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
        // AWAITING_RETRY joins the scan so a step waiting out its backoff is woken the same way a
        // TIMER is; classification (timeout vs timer vs retry) happens by status/type downstream.
        return stepExecutionEntityRepository
                .findAllByStatusInAndDeadlineAtLessThanEqual(
                        List.of(PENDING.name(), RUNNING.name(), AWAITING_RETRY.name()), now)
                .stream().map(this::map).toList();
    }

    @Override
    public List<StepExecution> findDueByProcessId(String processId, LocalDateTime now) {
        // Intentionally PENDING/RUNNING only: this feeds the timeout check, which must never see an
        // AWAITING_RETRY step (its startedAt is the failed attempt's and would read as expired).
        return stepExecutionEntityRepository
                .findAllByProcessIdAndStatusInAndDeadlineAtLessThanEqual(
                        processId, List.of(PENDING.name(), RUNNING.name()), now)
                .stream().map(this::map).toList();
    }

    @Override
    public List<StepExecution> findDueRetriesByProcessId(String processId, LocalDateTime now) {
        return stepExecutionEntityRepository
                .findAllByProcessIdAndStatusInAndDeadlineAtLessThanEqual(
                        processId, List.of(AWAITING_RETRY.name()), now)
                .stream().map(this::map).toList();
    }

    @Override
    public List<StepExecution> findWaitingForMessage(String messageName, String correlationKey) {
        if (correlationKey == null) {
            // SQL equality never matches null; short-circuit rather than issue a query that
            // cannot return anything.
            return List.of();
        }
        return stepExecutionEntityRepository
                .findAllByStatusAndAwaitingMessageNameAndAwaitingCorrelationKey(
                        PENDING.name(), messageName, correlationKey)
                .stream().map(this::map).toList();
    }

    /**
     * The step types whose waiting is machine work: a request went out to a worker and an answer
     * is owed. The same two the fallback deadline is applied to (see {@code StepTimeoutDefaults}),
     * because it is the same question — everything else waits without a deadline on purpose.
     */
    private static final List<String> AWAITING_A_WORKER =
            List.of(StepType.ACTION.name(), StepType.RULE.name());

    @Override
    public long countStalled(LocalDateTime startedBefore) {
        return stepExecutionEntityRepository.countStalled(
                List.of(PENDING.name(), RUNNING.name()), startedBefore, AWAITING_A_WORKER);
    }
}
