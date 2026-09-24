package io.mateu.workflow.application.out;

import io.mateu.workflow.application.sync.Invocation;

import java.time.LocalDateTime;
import java.util.Optional;

/** Where synchronous invocations are kept, keyed by id and by (definition, idempotency key). */
public interface InvocationRepository {

    Optional<Invocation> findById(String id);

    Optional<Invocation> findByKey(String workflowDefinitionId, String idempotencyKey);

    /** The invocation that started this process, if it was started synchronously. */
    Optional<Invocation> findByProcessId(String processId);

    /**
     * Records the invocation and runs {@code creation} — the process creation — in the same
     * transaction, so either both exist or neither does: an invocation never points at a process
     * that was not created, and a process started for an invocation always has its record.
     *
     * @throws DuplicateInvocationException if the (definition, key) pair is already taken — by a
     *         concurrent request, typically — in which case nothing was written
     */
    void createWith(Invocation invocation, Runnable creation);

    /** Deletes invocations whose {@code expiresAt} has passed; returns how many. */
    int purgeExpired(LocalDateTime now);

    /** The (definition, idempotency key) pair is already taken. */
    class DuplicateInvocationException extends RuntimeException {
        public DuplicateInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
