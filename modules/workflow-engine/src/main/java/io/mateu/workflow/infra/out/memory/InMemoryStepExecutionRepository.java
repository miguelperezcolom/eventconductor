package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryStepExecutionRepository implements StepExecutionRepository {

    @Lazy
    @Autowired
    private ProcessDomainEventUseCase processDomainEventUseCase;

    private final Map<String, StepExecution> store = new ConcurrentHashMap<>();

    @Override
    public Optional<StepExecution> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(StepExecution stepExecution) {
        store.put(stepExecution.id(), stepExecution);
        stepExecution.popEvents().forEach(event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)));
        return stepExecution.id();
    }

    @Override
    public List<StepExecution> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public List<StepExecution> findByProcess(Process process) {
        return store.values().stream()
                .filter(se -> process.id().equals(se.getProcessId()))
                .sorted(java.util.Comparator.comparingLong(StepExecution::getOrder))
                .toList();
    }

    @Override
    public List<StepExecution> findPendingOrRunning() {
        return store.values().stream()
                .filter(InMemoryStepExecutionRepository::isLive)
                .toList();
    }

    @Override
    public List<StepExecution> findPendingOrRunningByProcessId(String processId) {
        return store.values().stream()
                .filter(se -> processId.equals(se.getProcessId()))
                .filter(InMemoryStepExecutionRepository::isLive)
                .toList();
    }

    @Override
    public List<StepExecution> findDue(java.time.LocalDateTime now) {
        // Mirror the JPA scan: PENDING/RUNNING (timeouts, timers) plus AWAITING_RETRY (backoffs).
        return store.values().stream()
                .filter(se -> isLive(se) || se.getStatus() == StepExecutionStatus.AWAITING_RETRY)
                .filter(se -> se.getDeadlineAt() != null && !se.getDeadlineAt().isAfter(now))
                .toList();
    }

    @Override
    public List<StepExecution> findDueByProcessId(String processId, java.time.LocalDateTime now) {
        // PENDING/RUNNING only — feeds the timeout check, which must not see AWAITING_RETRY steps.
        return store.values().stream()
                .filter(se -> processId.equals(se.getProcessId()))
                .filter(InMemoryStepExecutionRepository::isLive)
                .filter(se -> se.getDeadlineAt() != null && !se.getDeadlineAt().isAfter(now))
                .toList();
    }

    @Override
    public List<StepExecution> findDueRetriesByProcessId(String processId, java.time.LocalDateTime now) {
        return store.values().stream()
                .filter(se -> processId.equals(se.getProcessId()))
                .filter(se -> se.getStatus() == StepExecutionStatus.AWAITING_RETRY)
                .filter(se -> se.getDeadlineAt() != null && !se.getDeadlineAt().isAfter(now))
                .toList();
    }

    @Override
    public List<StepExecution> findWaitingForMessage(String messageName, String correlationKey) {
        return store.values().stream()
                .filter(se -> se.getStatus() == StepExecutionStatus.PENDING)
                .filter(se -> messageName.equals(se.getAwaitingMessageName()))
                // A null stored key matches nothing, mirroring the SQL equality the JPA
                // adapter relies on — an unevaluable correlation expression stays fail-closed.
                .filter(se -> se.getAwaitingCorrelationKey() != null
                        && se.getAwaitingCorrelationKey().equals(correlationKey))
                .toList();
    }

    private static boolean isLive(StepExecution stepExecution) {
        return stepExecution.getStatus() == StepExecutionStatus.PENDING
                || stepExecution.getStatus() == StepExecutionStatus.RUNNING;
    }
}
