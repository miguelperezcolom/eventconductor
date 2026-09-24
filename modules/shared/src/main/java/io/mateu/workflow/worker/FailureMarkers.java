package io.mateu.workflow.worker;

/**
 * Conventions a worker's failure reason can carry to the engine.
 *
 * <p>{@link #NON_RETRYABLE}: a failure that retrying cannot fix — a 400 from an HTTP endpoint, a
 * declared business error the contract marks as final. The engine then fails the step at once instead
 * of spending its {@code retries} on it (compensation, if any, runs as usual). A prefix on the reason
 * rather than a new protocol field, so it travels unchanged through every transport and every worker
 * version: a worker that does not know it simply never sends it.
 */
public final class FailureMarkers {

    public static final String NON_RETRYABLE = "[non-retryable] ";

    public static boolean isNonRetryable(String reason) {
        return reason != null && reason.startsWith(NON_RETRYABLE);
    }

    public static String nonRetryable(String reason) {
        return isNonRetryable(reason) ? reason : NON_RETRYABLE + (reason == null ? "" : reason);
    }

    private FailureMarkers() {
    }
}
