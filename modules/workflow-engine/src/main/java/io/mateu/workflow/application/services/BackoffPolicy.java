package io.mateu.workflow.application.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes how long a failed step waits before its next attempt. Exponential backoff with a cap and
 * optional jitter, so a worker that fails fast is not hammered in a tight loop and a fleet of steps
 * that failed together (a downstream outage) do not all retry on the same tick.
 *
 * <p>Kept out of the {@code StepExecution} aggregate on purpose: the delay depends on configuration
 * and (for jitter) randomness, neither of which belongs in a pure, replay-diagnosable domain object.
 * The domain is handed the resulting {@link Duration}.
 *
 * <p>Delay for the retry that follows attempt {@code n} (1-based failed-attempt count):
 * {@code min(maxMs, baseMs * multiplier^(n-1))}, then multiplied by a random factor in
 * {@code [1 - jitter, 1 + jitter]}.
 */
@Service
public class BackoffPolicy {

    private final long baseMs;
    private final double multiplier;
    private final long maxMs;
    private final double jitter;

    public BackoffPolicy(
            @Value("${workflow.retry.backoff-base-ms:1000}") long baseMs,
            @Value("${workflow.retry.backoff-multiplier:2.0}") double multiplier,
            @Value("${workflow.retry.backoff-max-ms:60000}") long maxMs,
            @Value("${workflow.retry.backoff-jitter:0.2}") double jitter) {
        this.baseMs = Math.max(0, baseMs);
        this.multiplier = multiplier < 1.0 ? 1.0 : multiplier;
        this.maxMs = Math.max(this.baseMs, maxMs);
        this.jitter = Math.min(1.0, Math.max(0.0, jitter));
    }

    /**
     * @param failedAttemptCount how many attempts have failed so far (1 for the first failure). The
     *                           first retry (failedAttemptCount = 1) waits {@code baseMs}.
     */
    public Duration nextDelay(int failedAttemptCount) {
        int exponent = Math.max(0, failedAttemptCount - 1);
        // Cap the exponent before pow() so a large retry count cannot overflow to Infinity.
        double raw = exponent >= 63 ? Double.MAX_VALUE : baseMs * Math.pow(multiplier, exponent);
        long capped = raw >= maxMs ? maxMs : (long) raw;
        long withJitter = applyJitter(capped);
        return Duration.ofMillis(Math.max(0, withJitter));
    }

    private long applyJitter(long millis) {
        if (jitter == 0.0 || millis == 0) {
            return millis;
        }
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Math.round(millis * factor);
    }
}
