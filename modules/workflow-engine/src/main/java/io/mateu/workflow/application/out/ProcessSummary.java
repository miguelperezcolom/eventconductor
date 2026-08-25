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
        LocalDateTime finished) {

    public static ProcessSummary from(Process process) {
        return new ProcessSummary(
                process.id(),
                process.getName(),
                process.getStatus(),
                process.getCompletionPercentage(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished());
    }
}
