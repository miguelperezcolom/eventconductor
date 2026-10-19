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
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryLogMessageRepository implements LogMessageRepository {

    private final Map<String, LogMessage> store = new ConcurrentHashMap<>();

    @Override
    public Optional<LogMessage> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /** Masks the process's sensitive values in a line before it is stored. */
    io.mateu.workflow.application.services.SensitiveLogRedaction redaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void redaction(@org.springframework.context.annotation.Lazy io.mateu.workflow.application.services.SensitiveLogRedaction redaction) {
        this.redaction = redaction;
    }

    @Override
    public String save(LogMessage message) {
        if (redaction != null) {
            message = redaction.redact(message);
        }
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

    @Override
    public List<LogMessage> findByProcessId(String processId) {
        return store.values().stream()
                .filter(msg -> processId.equals(msg.getProcessId()))
                .toList();
    }
}
