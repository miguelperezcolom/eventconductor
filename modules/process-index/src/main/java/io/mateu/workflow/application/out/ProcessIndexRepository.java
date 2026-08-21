package io.mateu.workflow.application.out;

import io.mateu.workflow.application.readmodel.ProcessIndexRow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CQRS process-index read store. Written only by the projector (one upsert per status change),
 * read by every process-listing / lookup / count query. A port, so it can live in the same database
 * as the write model (non-sharded, the default) or in a dedicated read database fed by a fanned-out
 * projector (sharded). Idempotent: a redelivered event upserts the same latest state.
 */
public interface ProcessIndexRepository {

    /** Insert or update the row for a process. Last write by the projector wins. */
    void upsert(ProcessIndexRow row);

    /** Processes currently in any of the given statuses — e.g. RUNNING/PENDING for "what is running". */
    List<ProcessIndexRow> findByStatusIn(Collection<String> statuses);

    /** Same, scoped to one workflow definition. */
    List<ProcessIndexRow> findByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                Collection<String> statuses);

    /** Point lookup by business key — the routing key, unique per process. */
    Optional<ProcessIndexRow> findByBusinessKey(String businessKey);

    /** Point lookup by process id (the row's primary key) — how a command finds its owning shard. */
    Optional<ProcessIndexRow> findByProcessId(String processId);

    /** How many processes are in each status — the operator's at-a-glance fleet view. */
    Map<String, Long> countByStatus();

    /**
     * One page of the operator listing, answered from the index — newest first, matching the same
     * text and the same "only errors" toggle the write-side listing matches.
     *
     * <p>This is what makes the listing fleet-wide. The write side can only ever answer for its own
     * database, which in a sharded deployment is one shard; the index is the only store that has
     * seen every shard.
     *
     * <p>Optional, with a default of "not supported", because an index store that cannot page is
     * still a perfectly good index store for the lookups above — and the listing needs a definite
     * answer to whether it may use it, not an empty page that looks like no processes.
     *
     * @param searchText matched against name and business key, case-insensitively; null or blank
     *                   matches everything
     * @param onlyErrors keep only processes in {@code ERROR}
     * @param page       zero-based page number
     * @param size       rows per page; zero or less means all of them
     * @return the page, or empty if this store does not support paging
     */
    default Optional<ProcessIndexPage> search(String searchText, boolean onlyErrors, int page, int size) {
        return Optional.empty();
    }

    /** One page of {@link #search}, with the total the filter matched and the page actually served. */
    record ProcessIndexPage(List<ProcessIndexRow> content, long totalElements,
                            int pageNumber, int pageSize) {
    }
}
