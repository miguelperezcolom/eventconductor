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
                .filter(se -> se.getStatus() == StepExecutionStatus.PENDING
                        || se.getStatus() == StepExecutionStatus.RUNNING)
                .toList();
    }
}
