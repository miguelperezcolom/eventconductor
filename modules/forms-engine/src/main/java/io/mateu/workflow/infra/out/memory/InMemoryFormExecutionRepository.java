package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "forms.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryFormExecutionRepository implements FormExecutionRepository {

    private final ConcurrentHashMap<String, FormExecution> store = new ConcurrentHashMap<>();

    @Override
    public Optional<FormExecution> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(FormExecution execution) {
        store.put(execution.id(), execution);
        return execution.id();
    }

    @Override
    public List<FormExecution> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }
}
