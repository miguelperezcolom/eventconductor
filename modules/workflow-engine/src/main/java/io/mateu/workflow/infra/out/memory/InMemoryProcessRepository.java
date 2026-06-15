package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.domain.aggregates.Process;
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
public class InMemoryProcessRepository implements ProcessRepository {

    // @Lazy breaks the circular dependency:
    // InMemoryProcessRepository → ProcessDomainEventUseCase → handlers → use cases → InMemoryProcessRepository
    @Lazy
    @Autowired
    private ProcessDomainEventUseCase processDomainEventUseCase;

    private final Map<String, Process> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Process> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(Process process) {
        store.put(process.id(), process);
        process.popEvents().forEach(event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)));
        return process.id();
    }

    @Override
    public List<Process> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public Optional<Process> findByBusinessKey(String businessKey) {
        return store.values().stream()
                .filter(p -> businessKey.equals(p.getBusinessKey()))
                .findFirst();
    }
}
