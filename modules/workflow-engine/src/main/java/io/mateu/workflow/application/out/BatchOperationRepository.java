package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Where batch operations are kept: {@code batch_operation} on a database (jpa), a map in memory.
 *
 * <p>A cancellation is the one write that can race the runner, and it must win: {@link #saveProgress}
 * records the runner's counters and cursor whatever happened meanwhile, but never takes a batch out of
 * {@code CANCELLED} — it answers with the status it found, so the runner stops.
 */
public interface BatchOperationRepository {

    void create(BatchOperation operation);

    Optional<BatchOperation> findById(String id);

    /** The most recent first, at most {@code limit}. */
    List<BatchOperation> findRecent(int limit);

    /** The batch the runner should work on next: the oldest RUNNING one, else the oldest CREATED one. */
    Optional<BatchOperation> findNextActive();

    /**
     * Records the runner's progress — counters, cursor, errors, and its status unless the stored one is
     * already final (an operator cancelled it): then the stored status stays.
     *
     * @return the status now stored
     */
    BatchOperationStatus saveProgress(BatchOperation operation);

    /**
     * Cancels a CREATED or RUNNING batch.
     *
     * @return true if it was active and is now cancelled
     */
    boolean cancel(String id, LocalDateTime at);
}
