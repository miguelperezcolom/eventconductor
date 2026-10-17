package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code events.yaml} triggers for memory persistence: one JVM, so a map by source is the whole store. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventTriggerRepository implements EventTriggerRepository {

    private final Map<String, List<EventFileTrigger>> bySource = new TreeMap<>();

    @Override
    public synchronized List<EventFileTrigger> findAll() {
        return bySource.values().stream().flatMap(List::stream).toList();
    }

    @Override
    public synchronized void replace(String source, List<EventFileTrigger> triggers) {
        if (triggers == null || triggers.isEmpty()) {
            bySource.remove(source);
        } else {
            bySource.put(source, List.copyOf(triggers));
        }
    }
}
