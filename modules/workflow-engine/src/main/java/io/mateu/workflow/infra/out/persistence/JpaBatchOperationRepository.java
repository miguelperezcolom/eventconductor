package io.mateu.workflow.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.mateu.workflow.application.out.BatchOperationRepository;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.security.AuthorizationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code batch_operation} on the engine's database (jpa persistence). */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
public class JpaBatchOperationRepository implements BatchOperationRepository {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final BatchOperationEntityRepository repository;

    public JpaBatchOperationRepository(BatchOperationEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void create(BatchOperation operation) {
        repository.save(toEntity(operation));
    }

    @Override
    public Optional<BatchOperation> findById(String id) {
        return repository.findById(id).map(JpaBatchOperationRepository::toDomain);
    }

    @Override
    public List<BatchOperation> findRecent(int limit) {
        return repository.findRecent(PageRequest.of(0, Math.max(1, limit))).stream()
                .map(JpaBatchOperationRepository::toDomain).toList();
    }

    @Override
    public Optional<BatchOperation> findNextActive() {
        var running = repository.findByStatusOldestFirst(List.of(BatchOperationStatus.RUNNING.name()), PageRequest.of(0, 1));
        if (!running.isEmpty()) {
            return Optional.of(toDomain(running.getFirst()));
        }
        return repository.findByStatusOldestFirst(List.of(BatchOperationStatus.CREATED.name()), PageRequest.of(0, 1))
                .stream().findFirst().map(JpaBatchOperationRepository::toDomain);
    }

    @Override
    @Transactional
    public BatchOperationStatus saveProgress(BatchOperation operation) {
        var stored = repository.findForUpdate(operation.id()).orElse(null);
        var entity = toEntity(operation);
        if (stored != null && BatchOperationStatus.CANCELLED.name().equals(stored.getStatus())) {
            // An operator cancelled it while the runner worked: the counters are kept, the cancellation too.
            entity.setStatus(stored.getStatus());
            entity.setFinishedAt(stored.getFinishedAt());
            entity.setFailure(stored.getFailure());
        }
        repository.save(entity);
        return BatchOperationStatus.valueOf(entity.getStatus());
    }

    @Override
    @Transactional
    public boolean cancel(String id, LocalDateTime at) {
        var stored = repository.findForUpdate(id).orElse(null);
        if (stored == null || !BatchOperationStatus.valueOf(stored.getStatus()).isActive()) {
            return false;
        }
        stored.setStatus(BatchOperationStatus.CANCELLED.name());
        stored.setFinishedAt(at);
        stored.setUpdatedAt(at);
        repository.save(stored);
        return true;
    }

    static BatchOperationEntity toEntity(BatchOperation op) {
        return new BatchOperationEntity(op.id(), op.type().name(), op.status().name(), write(op.filter()),
                op.parameters().isEmpty() ? null : write(op.parameters()), op.matched(), op.succeeded(), op.failed(),
                op.skipped(), op.skippedByReason().isEmpty() ? null : write(op.skippedByReason()), op.cursor(),
                op.errors().isEmpty() ? null : write(op.errors()), op.createdBy(),
                op.caller() == null ? null : write(op.caller()), op.createdAt(), op.startedAt(), op.finishedAt(),
                op.updatedAt(), op.failure());
    }

    static BatchOperation toDomain(BatchOperationEntity e) {
        return new BatchOperation(e.getId(), BatchOperationType.valueOf(e.getOpType()),
                read(e.getFilterJson(), new TypeReference<BatchOperationFilter>() {}),
                read(e.getParametersJson(), new TypeReference<Map<String, String>>() {}),
                BatchOperationStatus.valueOf(e.getStatus()), e.getMatched(), e.getSucceeded(), e.getFailed(),
                e.getSkipped(), read(e.getSkippedByReasonJson(), new TypeReference<Map<String, Long>>() {}),
                e.getCursorId(), read(e.getErrorsJson(), new TypeReference<List<String>>() {}),
                read(e.getCallerJson(), new TypeReference<AuthorizationContext>() {}), e.getCreatedAt(),
                e.getStartedAt(), e.getFinishedAt(), e.getUpdatedAt(), e.getFailure());
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise " + value, e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read a stored batch operation field: " + e.getMessage(), e);
        }
    }
}
