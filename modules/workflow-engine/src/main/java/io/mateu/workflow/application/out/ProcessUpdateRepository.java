package io.mateu.workflow.application.out;

import io.mateu.workflow.application.updates.ProcessUpdate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Where process updates are recorded, by id and by {@code (process, idempotency key)}. */
public interface ProcessUpdateRepository {

    Optional<ProcessUpdate> findById(String id);

    Optional<ProcessUpdate> findByKey(String processId, String idempotencyKey);

    /** A process's updates, in the order they were requested. */
    List<ProcessUpdate> findByProcessId(String processId);

    /** Those of {@code ids} that have reached an outcome — one query for every waiting caller of a pod. */
    List<ProcessUpdate> findFinishedAmong(Collection<String> ids);

    /**
     * Records a new update.
     *
     * @throws DuplicateUpdateException if {@code (process, idempotency key)} is already taken — by a
     *         concurrent retry, typically — in which case nothing was written
     */
    void insert(ProcessUpdate update);

    /** Writes an update's outcome (joining the caller's transaction, if any). */
    void save(ProcessUpdate update);

    /**
     * Runs {@code work} in one transaction — the update-with-start's process creation and its update
     * row. Memory mode has no transaction: it runs under a monitor.
     */
    void inTransaction(Runnable work);

    /**
     * Frees the idempotency keys whose retention has passed: the key becomes the update's id, so the
     * caller's key can be used again while the record itself stays for the process's history.
     *
     * @return how many keys were freed
     */
    int expireKeys(LocalDateTime now);

    /** Deletes the updates of these processes (the retention purge deleted them); returns how many. */
    int deleteByProcessIds(Collection<String> processIds);

    /** The {@code (process, idempotency key)} pair is already taken. */
    class DuplicateUpdateException extends RuntimeException {
        public DuplicateUpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
