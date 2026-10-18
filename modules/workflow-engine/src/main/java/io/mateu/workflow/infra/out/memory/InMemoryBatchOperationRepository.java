package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.BatchOperationRepository;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Batch operations in the heap (memory persistence): gone with the JVM, like the processes they act on. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryBatchOperationRepository implements BatchOperationRepository {

    private final Map<String, BatchOperation> store = new ConcurrentHashMap<>();

    @Override
    public void create(BatchOperation operation) {
        store.put(operation.id(), operation);
    }

    @Override
    public Optional<BatchOperation> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<BatchOperation> findRecent(int limit) {
        return store.values().stream()
                .sorted(Comparator.comparing(BatchOperation::createdAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public Optional<BatchOperation> findNextActive() {
        return store.values().stream()
                .filter(op -> op.status().isActive())
                .min(Comparator.comparing((BatchOperation op) -> op.status() == BatchOperationStatus.RUNNING ? 0 : 1)
                        .thenComparing(BatchOperation::createdAt));
    }

    @Override
    public BatchOperationStatus saveProgress(BatchOperation operation) {
        var saved = store.compute(operation.id(), (id, stored) -> {
            if (stored != null && stored.status() == BatchOperationStatus.CANCELLED) {
                return new BatchOperation(operation.id(), operation.type(), operation.filter(), operation.parameters(),
                        BatchOperationStatus.CANCELLED, operation.matched(), operation.succeeded(), operation.failed(),
                        operation.skipped(), operation.skippedByReason(), operation.cursor(), operation.errors(),
                        operation.caller(), operation.createdAt(), operation.startedAt(), stored.finishedAt(),
                        operation.updatedAt(), stored.failure());
            }
            return operation;
        });
        return saved.status();
    }

    @Override
    public boolean cancel(String id, LocalDateTime at) {
        var cancelled = new boolean[1];
        store.computeIfPresent(id, (key, stored) -> {
            if (!stored.status().isActive()) {
                return stored;
            }
            cancelled[0] = true;
            return new BatchOperation(stored.id(), stored.type(), stored.filter(), stored.parameters(),
                    BatchOperationStatus.CANCELLED, stored.matched(), stored.succeeded(), stored.failed(),
                    stored.skipped(), stored.skippedByReason(), stored.cursor(), stored.errors(), stored.caller(),
                    stored.createdAt(), stored.startedAt(), at, at, null);
        });
        return cancelled[0];
    }
}
