package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory")
public class InMemoryLogMessageRepository implements LogMessageRepository {

    private final Map<String, LogMessage> store = new ConcurrentHashMap<>();

    @Override
    public Optional<LogMessage> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(LogMessage message) {
        store.put(message.id(), message);
        return message.id();
    }

    @Override
    public List<LogMessage> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }
}
