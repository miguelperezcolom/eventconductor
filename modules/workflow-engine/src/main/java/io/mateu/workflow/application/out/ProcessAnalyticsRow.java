package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;

/**
 * What analytics needs to know about a process: which definition it belongs to, how it ended, and
 * when. Not the variables, not the log, and not the workflow definition JSON — analytics reads
 * every process in its window, so a row that carries the definition with it is a report that costs
 * hundreds of megabytes to produce.
 *
 * @see ProcessRepository#findAnalyticsRows
 */
public record ProcessAnalyticsRow(
        String id,
        String name,
        String workflowDefinitionId,
        ProcessStatus status,
        LocalDateTime created,
        LocalDateTime started,
        LocalDateTime finished) {

    public static ProcessAnalyticsRow from(Process process) {
        return new ProcessAnalyticsRow(
                process.getId(),
                process.getName(),
                process.getWorkflowDefinitionId(),
                process.getStatus(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished());
    }
}
