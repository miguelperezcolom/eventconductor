package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.updates.ProcessUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Process updates in the database. {@link #insert} flushes at once, so a key taken by a concurrent
 * request fails fast on the unique constraint; {@link #save} joins the transaction it is called in — the
 * process lock's, when the owner records an outcome together with the process it changed.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaProcessUpdateRepository implements ProcessUpdateRepository {

    final ProcessUpdateEntityRepository repository;
    final TransactionTemplate transactionTemplate;

    @Override
    public Optional<ProcessUpdate> findById(String id) {
        return repository.findById(id).map(JpaProcessUpdateRepository::map);
    }

    @Override
    public Optional<ProcessUpdate> findByKey(String processId, String idempotencyKey) {
        return repository.findByProcessIdAndIdempotencyKey(processId, idempotencyKey).map(JpaProcessUpdateRepository::map);
    }

    @Override
    public List<ProcessUpdate> findByProcessId(String processId) {
        return repository.findByProcessIdOrderByRequestedAtAscCompletedAtAsc(processId).stream()
                .map(JpaProcessUpdateRepository::map).toList();
    }

    @Override
    public List<ProcessUpdate> findFinishedAmong(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findFinishedAmong(ids).stream().map(JpaProcessUpdateRepository::map).toList();
    }

    @Override
    public void insert(ProcessUpdate update) {
        try {
            transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(entityOf(update)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUpdateException("Idempotency key already used: " + update.idempotencyKey(), e);
        }
    }

    @Override
    public void save(ProcessUpdate update) {
        try {
            repository.saveAndFlush(entityOf(update));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUpdateException("Idempotency key already used: " + update.idempotencyKey(), e);
        }
    }

    @Override
    public void inTransaction(Runnable work) {
        try {
            transactionTemplate.executeWithoutResult(status -> work.run());
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUpdateException("A concurrent request took the same key", e);
        }
    }

    @Override
    public int expireKeys(LocalDateTime now) {
        Integer freed = transactionTemplate.execute(status -> repository.expireKeys(now));
        return freed == null ? 0 : freed;
    }

    @Override
    public int deleteByProcessIds(Collection<String> processIds) {
        if (processIds == null || processIds.isEmpty()) {
            return 0;
        }
        Integer deleted = transactionTemplate.execute(status -> repository.deleteByProcessIds(processIds));
        return deleted == null ? 0 : deleted;
    }

    static ProcessUpdateEntity entityOf(ProcessUpdate update) {
        return new ProcessUpdateEntity(update.id(), update.processId(), update.workflowDefinitionId(), update.name(),
                update.idempotencyKey(), update.requestHash(), update.payload(),
                update.status() == null ? ProcessUpdate.Status.REQUESTED.name() : update.status().name(),
                update.result(), update.error(), update.requestedBy(), update.withStart(), update.requestedAt(),
                update.completedAt(), update.expiresAt());
    }

    static ProcessUpdate map(ProcessUpdateEntity entity) {
        return new ProcessUpdate(entity.getId(), entity.getProcessId(), entity.getWorkflowDefinitionId(),
                entity.getName(), entity.getIdempotencyKey(), entity.getRequestHash(), entity.getPayload(),
                ProcessUpdate.Status.valueOf(entity.getStatus()), entity.getResult(), entity.getError(),
                entity.getRequestedBy(), entity.isWithStart(), entity.getRequestedAt(), entity.getCompletedAt(),
                entity.getExpiresAt());
    }
}
