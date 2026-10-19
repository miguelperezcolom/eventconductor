package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

import java.util.Map;

/**
 * An operator asked to move a live process onto another version of its definition (2.31): each step of
 * the version it runs becomes a step of the target, by {@code stepMapping} or by id, keeping what the
 * process has done. From the UI, the REST API, an MCP tool or a MIGRATE batch.
 *
 * <p>Published rather than executed in place, like the other operator commands: carried out by the pod
 * that owns the process, under its lock, where it is planned again — the process may have moved on.
 *
 * @param targetVersion   the version of the process's definition to move to; null for the definition as it is now
 * @param stepMapping     source step id → target step id, over the default (same id, compatible type); a blank
 *                        target says the step has no counterpart
 * @param variableMapping variables to set on the process as part of the move (name → value)
 * @param reason          why — mandatory for a manual operation, recorded in the process's audit trail
 * @param requestedBy     who asked (the caller's subject at the door), or null when nobody was identified
 */
public record MigrateProcessRequested(String processId, Integer targetVersion, Map<String, String> stepMapping,
                                      Map<String, String> variableMapping, String reason, String requestedBy)
        implements DomainEvent {

    public MigrateProcessRequested {
        stepMapping = stepMapping == null ? Map.of() : Map.copyOf(stepMapping);
        variableMapping = variableMapping == null ? Map.of() : Map.copyOf(variableMapping);
    }

    @Override
    public String partitionKey() {
        return processId;
    }
}
