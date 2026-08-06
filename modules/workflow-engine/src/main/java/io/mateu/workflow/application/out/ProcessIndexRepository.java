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

    /** How many processes are in each status — the operator's at-a-glance fleet view. */
    Map<String, Long> countByStatus();
}
