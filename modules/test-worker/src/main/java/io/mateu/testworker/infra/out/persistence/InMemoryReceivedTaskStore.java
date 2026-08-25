package io.mateu.testworker.infra.out.persistence;

import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.domain.ReceivedTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Received tasks in a bounded map, newest first. The default, and bounded on purpose: a worker
 * pointed at a load test would otherwise hold every task it has ever been handed until the heap
 * ran out, which is a poor way for a test tool to end a test.
 */
@Service
@ConditionalOnProperty(name = "worker.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryReceivedTaskStore implements ReceivedTaskStore {

    private final Map<String, ReceivedTask> rows;

    public InMemoryReceivedTaskStore(@Value("${worker.received-tasks.remembered:5000}") int remembered) {
        this.rows = java.util.Collections.synchronizedMap(
                new LinkedHashMap<>(64, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ReceivedTask> eldest) {
                        return size() > remembered;
                    }
                });
    }

    @Override
    public int previousDeliveriesOf(String taskExecutionId) {
        var row = rows.get(taskExecutionId);
        return row == null || row.attempt() == null ? 0 : row.attempt();
    }

    @Override
    public Optional<ReceivedTask> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public String save(ReceivedTask row) {
        rows.put(row.id(), row);
        return row.id();
    }

    @Override
    public List<ReceivedTask> findAll() {
        synchronized (rows) {
            return rows.values().stream()
                    .sorted(Comparator.comparing(ReceivedTask::receivedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(ReceivedTask::id))
                    .toList();
        }
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.stream().filter(Objects::nonNull).forEach(rows::remove);
    }
}
