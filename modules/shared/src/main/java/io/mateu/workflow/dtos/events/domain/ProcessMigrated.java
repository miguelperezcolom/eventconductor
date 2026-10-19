package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A live process was moved onto another version of its definition (2.31). Written to the outbox with the
 * migration, so whoever follows the engine's domain events learns of it in order with the steps it
 * re-armed and re-dispatched; the process index learns the new version from the
 * {@link ProcessStatusChanged} emitted beside it.
 *
 * @param fromVersion the version it ran
 * @param toVersion   the version it runs now
 * @param stepMapping the mapping applied: source step id → target step id (absent: no counterpart)
 * @param requestedBy who asked, or null
 * @param occurredAt  when, on the engine's clock
 */
public record ProcessMigrated(String processId, String workflowDefinitionId, int fromVersion, int toVersion,
                              Map<String, String> stepMapping, String requestedBy, LocalDateTime occurredAt)
        implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
