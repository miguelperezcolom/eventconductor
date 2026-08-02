package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * Somebody asked for a process to be cancelled — an operator through the UI or an MCP tool, or a
 * caller addressing it by business key.
 *
 * <p>Cancellation is a notification, not a transaction: the engine marks the process and its
 * live steps cancelled and tells the workers, but whether a worker actually abandons what it is
 * doing is the worker's business. Nothing here waits to find out.
 *
 * <p>Published rather than executed in place: the request lands on whichever pod took the call,
 * which is not the one that owns the process. Keyed, it is handled by the pod that does, so an
 * operator action goes through the same single writer as everything else.
 *
 * @param processId   preferred when the caller knows it — a process with no business key cannot
 *                    be addressed by one, and the key would then be null and route nowhere.
 * @param businessKey how an external caller names the process. Kept as the fallback key.
 */
public record ProcessCancellationRequested(String businessKey, String processId) implements DomainEvent {

    public ProcessCancellationRequested(String businessKey) {
        this(businessKey, null);
    }

    @Override
    public String partitionKey() {
        return processId != null && !processId.isBlank() ? processId : businessKey;
    }
}
