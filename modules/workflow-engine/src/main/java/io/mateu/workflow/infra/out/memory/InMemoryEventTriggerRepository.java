package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code events.yaml} and engine-created triggers for memory persistence: one JVM, so a map by source is the whole store. */
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
        if (EventFileTrigger.API_SOURCE.equals(source)) {
            throw new IllegalArgumentException("'" + source + "' holds the triggers created through the engine; no import replaces it");
        }
        if (triggers == null || triggers.isEmpty()) {
            bySource.remove(source);
        } else {
            bySource.put(source, List.copyOf(triggers));
        }
    }

    @Override
    public synchronized void save(EventFileTrigger trigger) {
        var key = trigger.key();
        var api = new ArrayList<>(bySource.getOrDefault(EventFileTrigger.API_SOURCE, List.of()));
        api.removeIf(t -> key.equals(t.key()));
        api.add(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, trigger.workflowId(), trigger.trigger()));
        bySource.put(EventFileTrigger.API_SOURCE, List.copyOf(api));
    }

    @Override
    public synchronized void deleteApiTriggers(Collection<String> keys) {
        var api = new ArrayList<>(bySource.getOrDefault(EventFileTrigger.API_SOURCE, List.of()));
        if (api.removeIf(t -> keys.contains(t.key()))) {
            if (api.isEmpty()) {
                bySource.remove(EventFileTrigger.API_SOURCE);
            } else {
                bySource.put(EventFileTrigger.API_SOURCE, List.copyOf(api));
            }
        }
    }
}
