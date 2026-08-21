package io.mateu.workflow.application.readmodel;

import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;

import java.time.LocalDateTime;

/**
 * One row of the process-index read model — the denormalised, query-optimised view of a process
 * that the CQRS projector maintains from {@link ProcessStatusChanged} events. It is what "list the
 * running processes", "find by business key", "what is stuck" and the analytics counts read,
 * instead of scanning the write-side {@code process_entity}/{@code step_execution_entity} tables.
 *
 * @param shardId the write shard the process lives on ({@code null} in a non-sharded deployment);
 *                lets a fanned-out projector record provenance and a command be routed back.
 */
public record ProcessIndexRow(
        String processId,
        String businessKey,
        String name,
        String workflowDefinitionId,
        int workflowDefinitionVersion,
        String status,
        int completionPercentage,
        LocalDateTime created,
        LocalDateTime started,
        LocalDateTime finished,
        LocalDateTime updatedAt,
        String shardId) {

    public static ProcessIndexRow from(ProcessStatusChanged e, LocalDateTime updatedAt, String shardId) {
        return new ProcessIndexRow(
                e.processId(), e.businessKey(), e.name(), e.workflowDefinitionId(), e.workflowDefinitionVersion(),
                e.status(), e.completionPercentage(), e.created(), e.started(), e.finished(),
                updatedAt, shardId);
    }
}
