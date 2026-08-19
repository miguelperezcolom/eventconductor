package io.mateu.testworker.infra.out.persistence;

import io.mateu.testworker.application.TaskOverrideStore;
import io.mateu.testworker.domain.TaskOverride;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overrides in a map. The default, so the worker runs with no database at all — which is the whole
 * configuration a suite driving everything from {@code TEST_CONFIG} needs.
 *
 * <p>They do not survive a restart, and that is the honest behaviour for a store with nowhere to
 * write: an override someone spent five minutes editing quietly disappearing is worse than a
 * worker that never claimed to keep it. Run with {@code worker.persistence=jpa} to keep them.
 */
@Service
@ConditionalOnProperty(name = "worker.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskOverrideStore implements TaskOverrideStore {

    private final Map<String, TaskOverride> rows = new ConcurrentHashMap<>();

    @Override
    public List<TaskOverride> enabled() {
        return rows.values().stream().filter(TaskOverride::enabled).toList();
    }

    @Override
    public Optional<TaskOverride> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public String save(TaskOverride row) {
        var id = row.id() == null || row.id().isBlank() ? UUID.randomUUID().toString() : row.id();
        rows.put(id, new TaskOverride(id, row.name(), row.workflowDefinitionId(), row.stepId(),
                row.taskId(), row.enabled(), row.durationMs(), row.outcome(), row.reason(),
                row.failuresBeforeSuccess(), row.replyTimes(), row.ignoreCancellation(),
                row.variables(), row.logs()));
        return id;
    }

    @Override
    public List<TaskOverride> findAll() {
        return rows.values().stream()
                .sorted((a, b) -> String.valueOf(a).compareToIgnoreCase(String.valueOf(b)))
                .toList();
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(rows::remove);
    }
}
