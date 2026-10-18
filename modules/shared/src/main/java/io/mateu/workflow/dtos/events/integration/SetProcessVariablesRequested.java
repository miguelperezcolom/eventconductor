package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * An operator asked to change the variables of a live process — from the UI, the REST API or an MCP
 * tool. {@code set} variables are written (created or overwritten), {@code remove} names are deleted.
 *
 * <p>Published rather than executed in place, like the other operator commands: the request arrives
 * at whichever pod took the call, and is carried out by the one that owns the process, under its lock.
 *
 * @param reason      why — mandatory for a manual operation, recorded in the process's audit trail
 * @param requestedBy who asked (the caller's subject at the door), or null when nobody was identified
 */
public record SetProcessVariablesRequested(String processId, List<Variable> set, List<String> remove,
                                           String reason, String requestedBy) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
