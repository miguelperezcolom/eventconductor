package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * What the process retention job needs from the store: find the finished process trees that are due,
 * and delete one tree — the processes and everything that belongs to them — atomically.
 *
 * <p>The decisions (which retention applies, whether a tree is purgeable at all) are the
 * {@code ProcessRetentionService}'s; the store's job is to answer the questions cheaply (each is an
 * index range or a primary-key lookup in the JPA store) and to refuse a purge whose premises changed
 * under it — an operator retried the process, a step went back to work — rather than delete it.
 */
public interface ProcessPurgeRepository {

    /** A process as the purge sees it: enough to decide, nothing to load. */
    record PurgeCandidate(String id, String workflowDefinitionId, ProcessStatus status, LocalDateTime finished) {
    }

    /**
     * Definition ids processes may belong to beyond the definitions the repository lists now — the
     * ones that were deleted — so their processes are still judged (by the engine defaults).
     */
    Set<String> knownWorkflowDefinitionIds();

    /**
     * The roots of process trees of {@code workflowDefinitionId} that ended in {@code status} before
     * {@code finishedBefore}, oldest first, after {@code after} (keyset: by finished, then id; null
     * for the first page). A root has no parent, or a parent step execution that no longer exists —
     * a child whose parent is still there is purged with its parent's tree, never on its own.
     */
    List<PurgeCandidate> findRoots(String workflowDefinitionId, ProcessStatus status, LocalDateTime finishedBefore,
                                   PurgeCandidate after, int limit);

    /** The direct children of these processes (the processes their PROCESS steps started), in any status. */
    List<PurgeCandidate> findChildren(Collection<String> processIds);

    /**
     * Deletes this tree and everything that belongs to its processes, all or nothing. Refuses —
     * deletes nothing and returns false — when any of them is no longer terminal with an end time,
     * still has a step at work or waiting to retry, holds or waits for a lock, or has outbox
     * messages not yet sent.
     */
    boolean purge(List<PurgeCandidate> tree);
}
