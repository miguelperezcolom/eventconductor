package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A bare map of processes — no event dispatch — for tests of code that only reads them. */
public class InMemoryProcessRepositoryForTests implements ProcessRepository {

    private final Map<String, Process> store = new ConcurrentHashMap<>();

    public void put(Process process) {
        store.put(process.getId(), process);
    }

    @Override
    public Optional<Process> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Process> findByBusinessKey(String businessKey) {
        return store.values().stream().filter(p -> businessKey.equals(p.getBusinessKey())).findFirst();
    }

    @Override
    public String save(Process process) {
        store.put(process.getId(), process);
        return process.getId();
    }

    @Override
    public List<Process> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }
}
