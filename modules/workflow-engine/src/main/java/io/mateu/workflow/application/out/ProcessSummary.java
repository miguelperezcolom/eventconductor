package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;

/**
 * The handful of fields a process listing actually paints — no variables, no log, and above all no
 * workflow definition JSON. The definition alone averages several KB per process, so a listing that
 * loads whole {@link Process} aggregates pays for the entire table in order to show ten rows.
 *
 * @see ProcessRepository#searchSummaries
 */
public record ProcessSummary(
        String id,
        String name,
        ProcessStatus status,
        int completionPercentage,
        LocalDateTime created,
        LocalDateTime started,
        LocalDateTime finished,
        /** The business identifier — what an API caller filters and looks processes up by. */
        String businessKey,
        /** The definition the process runs. */
        String workflowDefinitionId) {

    /** The shape before the listing carried the business key and the definition. */
    public ProcessSummary(String id, String name, ProcessStatus status, int completionPercentage,
                          LocalDateTime created, LocalDateTime started, LocalDateTime finished) {
        this(id, name, status, completionPercentage, created, started, finished, null, null);
    }

    public static ProcessSummary from(Process process) {
        return new ProcessSummary(
                process.id(),
                process.getName(),
                process.getStatus(),
                process.getCompletionPercentage(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished(),
                process.getBusinessKey(),
                process.getWorkflowDefinitionId());
    }
}
