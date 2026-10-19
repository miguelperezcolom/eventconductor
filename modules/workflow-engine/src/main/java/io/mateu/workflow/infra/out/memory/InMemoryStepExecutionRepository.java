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

    /**
     * Step execution ids by process id. Every per-process query used to scan the whole store, and
     * several run on every transition, so a transition cost grew with every step the JVM had ever
     * run: measured with the benchmark suite, sequential throughput fell from ~1,200 to ~50
     * processes/s across five batches of 4,000 processes. The index keeps them at the cost of the
     * one process's own steps.
     *
     * <p>Kept so a reader never sees less than the old scan did: an id is indexed <em>before</em>
     * its execution is stored and unindexed <em>after</em> it is removed, so an id without an
     * execution is possible (and skipped) but an execution without an id is not. Updates to a key
     * go through {@code compute}, which is atomic per key, so a set emptied and dropped by one
     * thread cannot swallow an id another thread is adding.
     */
    private final Map<String, java.util.Set<String>> idsByProcess = new ConcurrentHashMap<>();

    @Override
    public Optional<StepExecution> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(StepExecution stepExecution) {
        index(stepExecution.getProcessId(), stepExecution.id());
        var previous = store.put(stepExecution.id(), stepExecution);
        if (previous != null && previous.getProcessId() != null
                && !previous.getProcessId().equals(stepExecution.getProcessId())) {
            unindex(previous.getProcessId(), stepExecution.id());
        }
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
        ids.forEach(id -> {
            var removed = store.remove(id);
            if (removed != null) {
                unindex(removed.getProcessId(), id);
            }
        });
    }

    private void index(String processId, String id) {
        if (processId == null) {
            return;
        }
        idsByProcess.compute(processId, (key, ids) -> {
            var set = ids == null ? ConcurrentHashMap.<String>newKeySet() : ids;
            set.add(id);
            return set;
        });
    }

    private void unindex(String processId, String id) {
        if (processId == null) {
            return;
        }
        idsByProcess.computeIfPresent(processId, (key, ids) -> {
            ids.remove(id);
            return ids.isEmpty() ? null : ids;
        });
    }

    /** The executions of one process, from the index — the same set the full scan would find. */
    private java.util.stream.Stream<StepExecution> ofProcess(String processId) {
        var ids = idsByProcess.get(processId);
        if (ids == null) {
            return java.util.stream.Stream.empty();
        }
        return ids.stream()
                .map(store::get)
                .filter(java.util.Objects::nonNull)
                .filter(se -> processId.equals(se.getProcessId()));
    }

    @Override
    public List<StepExecution> findByProcessId(String processId) {
        return ofProcess(processId)
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
        return ofProcess(processId)
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
        return ofProcess(processId)
                .filter(InMemoryStepExecutionRepository::isLive)
                .filter(se -> se.getDeadlineAt() != null && !se.getDeadlineAt().isAfter(now))
                .toList();
    }

    @Override
    public List<StepExecution> findDueRetriesByProcessId(String processId, java.time.LocalDateTime now) {
        return ofProcess(processId)
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
