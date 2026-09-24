package io.mateu.workflow.application.sync;

import java.time.LocalDateTime;

/**
 * One synchronous invocation: a caller's request to start a process and wait for its reply,
 * identified for retries by {@code (workflowDefinitionId, idempotencyKey)}.
 *
 * <p>This is the request side only. The answer lives on the process ({@code ProcessReply}), written
 * with the transition that produced it; an invocation just remembers which process it started, so a
 * retry — or a caller that lost its connection — can find that answer again.
 *
 * @param id                   returned to the caller; the key of the result URL
 * @param workflowDefinitionId the definition invoked
 * @param idempotencyKey       the caller's key: a retry with it lands on the same process
 * @param requestHash          fingerprint of the request body, so a reused key with another body is refused
 * @param processId            the process this invocation started
 * @param deadlineAt           when the first caller stopped waiting (it got a 202 at that moment)
 * @param createdAt            when it was accepted
 * @param expiresAt            when the record may be purged (workflow.sync.retention)
 * @param callerTraceParent    the caller's W3C traceparent, for span links; null when not traced
 */
public record Invocation(
        String id,
        String workflowDefinitionId,
        String idempotencyKey,
        String requestHash,
        String processId,
        LocalDateTime deadlineAt,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        String callerTraceParent
) {
}
