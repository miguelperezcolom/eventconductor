package io.mateu.workflow.test;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a mocked task answers: completion with variables, a failure, or no reply at all. */
public sealed interface TaskOutcome {

    /** The task completed and writes these variables onto the process. */
    record Complete(Map<String, Object> variables) implements TaskOutcome {
        public Complete {
            variables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
        }
    }

    /**
     * The task failed with {@code code} — what a {@code TaskFailure} of the worker SDK reports. A
     * retryable failure spends the step's {@code retries} (with their backoff); a final one fails the
     * step at once.
     */
    record Fail(String code, String message, boolean retryable) implements TaskOutcome {

        /** The reason the engine records, exactly as {@code TaskFailure.reason()} builds it. */
        public String reason() {
            var reason = message == null || message.equals(code) ? code : code + ": " + message;
            return retryable ? reason : io.mateu.workflow.worker.FailureMarkers.nonRetryable(reason);
        }
    }

    /** The worker never answers: the step stays in flight until its timeout (or a test completes it). */
    record NoReply() implements TaskOutcome {
    }

    static TaskOutcome complete() {
        return new Complete(Map.of());
    }

    static TaskOutcome complete(Map<String, ?> variables) {
        return new Complete(variables == null ? Map.of() : new LinkedHashMap<>(variables));
    }

    static TaskOutcome fail(String code) {
        return new Fail(code, null, true);
    }

    static TaskOutcome fail(String code, boolean retryable) {
        return new Fail(code, null, retryable);
    }

    static TaskOutcome fail(String code, String message, boolean retryable) {
        return new Fail(code, message, retryable);
    }

    static TaskOutcome noReply() {
        return new NoReply();
    }
}
