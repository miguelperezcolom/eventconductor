package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "forms.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryFormRepository implements FormRepository {

    private final ConcurrentHashMap<String, Form> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Form> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(Form form) {
        store.put(form.id(), form);
        return form.id();
    }

    @Override
    public List<Form> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }
}
