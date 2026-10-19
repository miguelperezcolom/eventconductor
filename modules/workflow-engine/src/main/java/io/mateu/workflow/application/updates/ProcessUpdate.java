package io.mateu.workflow.application.updates;

import java.time.LocalDateTime;

/**
 * One update of a process, as recorded: what was asked, by whom, and how it ended. Written
 * {@link Status#REQUESTED} at the door, then — once, under the process lock — to its outcome, with the
 * result. A retry with the same idempotency key gets this record back instead of a second update.
 *
 * @param id                   the update's id; the key of {@code GET /workflow/api/updates/{id}}
 * @param processId            the process it is for
 * @param workflowDefinitionId that process's definition
 * @param name                 the update's name in the definition
 * @param idempotencyKey       the caller's key (the id when it gave none); freed after {@code workflow.sync.retention}
 * @param requestHash          fingerprint of {@code (name, payload)}: a reused key with another request is refused
 * @param payload              the payload as shown — its {@code sensitive} fields masked; never the raw one
 * @param status               where it stands
 * @param result               the reply (JSON) of a {@link Status#COMPLETED} update; null otherwise
 * @param error                why it was refused, not accepted or failed; null otherwise
 * @param requestedBy          the caller's subject, or null when nobody was identified
 * @param withStart            whether it came with an update-with-start that started the process
 * @param requestedAt          when it was accepted at the door
 * @param completedAt          when it reached its outcome; null while requested
 * @param expiresAt            until when its idempotency key is kept; null once freed
 */
public record ProcessUpdate(
        String id,
        String processId,
        String workflowDefinitionId,
        String name,
        String idempotencyKey,
        String requestHash,
        String payload,
        Status status,
        String result,
        String error,
        String requestedBy,
        boolean withStart,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        LocalDateTime expiresAt
) {

    public enum Status {
        /** Accepted at the door; the process has not applied it yet. */
        REQUESTED,
        /** Applied: its variables are written and {@code result} is its reply. */
        COMPLETED,
        /** A {@code validate} guard said no. Nothing changed. */
        REFUSED,
        /** The process was not accepting it (finished, failed, paused, or the step it needs was not waiting). Nothing changed. */
        NOT_ACCEPTED,
        /** An expression of the update could not be evaluated. Nothing changed. */
        FAILED;

        public boolean isFinal() {
            return this != REQUESTED;
        }
    }

    public boolean isFinal() {
        return status != null && status.isFinal();
    }

    /** The same update, finished. */
    public ProcessUpdate finished(Status newStatus, String newResult, String newError, LocalDateTime at) {
        return new ProcessUpdate(id, processId, workflowDefinitionId, name, idempotencyKey, requestHash, payload,
                newStatus, newResult, newError, requestedBy, withStart, requestedAt, at, expiresAt);
    }
}
