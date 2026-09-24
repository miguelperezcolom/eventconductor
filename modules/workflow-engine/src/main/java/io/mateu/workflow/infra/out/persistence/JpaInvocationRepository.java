package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.InvocationRepository;
import io.mateu.workflow.application.sync.Invocation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Invocations in the database. {@link #createWith} is one transaction around the invocation row and
 * the process creation: the row is inserted and flushed first, so a key taken by a concurrent
 * request fails fast on the unique constraint — before the process is created — and rolls back
 * cleanly; a creation that refuses rolls the row back with it.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaInvocationRepository implements InvocationRepository {

    final InvocationEntityRepository repository;
    final TransactionTemplate transactionTemplate;

    @Override
    public Optional<Invocation> findById(String id) {
        return repository.findById(id).map(JpaInvocationRepository::map);
    }

    @Override
    public Optional<Invocation> findByKey(String workflowDefinitionId, String idempotencyKey) {
        return repository.findByWorkflowDefinitionIdAndIdempotencyKey(workflowDefinitionId, idempotencyKey)
                .map(JpaInvocationRepository::map);
    }

    @Override
    public void createWith(Invocation invocation, Runnable creation) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                repository.saveAndFlush(new InvocationEntity(invocation.id(), invocation.workflowDefinitionId(),
                        invocation.idempotencyKey(), invocation.requestHash(), invocation.processId(),
                        invocation.deadlineAt(), invocation.createdAt(), invocation.expiresAt(),
                        invocation.callerTraceParent()));
                creation.run();
            });
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateInvocationException("Idempotency key already used: " + invocation.idempotencyKey(), e);
        }
    }

    @Override
    public int purgeExpired(LocalDateTime now) {
        Integer deleted = transactionTemplate.execute(status -> repository.deleteExpired(now));
        return deleted == null ? 0 : deleted;
    }

    static Invocation map(InvocationEntity entity) {
        return new Invocation(entity.getId(), entity.getWorkflowDefinitionId(), entity.getIdempotencyKey(),
                entity.getRequestHash(), entity.getProcessId(), entity.getDeadlineAt(), entity.getCreatedAt(),
                entity.getExpiresAt(), entity.getCallerTraceParent());
    }
}
