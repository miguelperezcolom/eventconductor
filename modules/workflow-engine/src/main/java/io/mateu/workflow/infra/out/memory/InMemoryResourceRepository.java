package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ResourceRepository;
import io.mateu.workflow.domain.aggregates.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory")
public class InMemoryResourceRepository implements ResourceRepository {

    private final Map<String, Resource> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Resource> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(Resource resource) {
        store.put(resource.id(), resource);
        return resource.id();
    }

    @Override
    public List<Resource> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }
}
