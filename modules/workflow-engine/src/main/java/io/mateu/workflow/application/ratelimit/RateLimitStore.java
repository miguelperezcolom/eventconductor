package io.mateu.workflow.application.ratelimit;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The permits of each rate-limited topic (a fixed window) and the tasks waiting for one. Taking a permit is
 * atomic across pods; so is claiming a waiting task (only one pod dispatches it).
 */
public interface RateLimitStore {

    /** Takes one of {@code topic}'s permits for the window {@code now} falls in, if one is left. */
    boolean tryAcquire(String topic, int permits, Duration period, LocalDateTime now);

    /** When {@code topic}'s current window closes, if it has one. */
    Optional<LocalDateTime> windowEnd(String topic, Duration period);

    /** A task waiting for a permit. */
    record Deferred(String stepExecutionId, String processId, String topic, int priority, LocalDateTime deferredAt) {
    }

    void defer(Deferred task);

    /** Whether any task of {@code topic} is waiting. */
    boolean hasDeferred(String topic);

    /** The topics with waiting tasks. */
    List<String> deferredTopics();

    /** Up to {@code max} waiting tasks of {@code topic}, higher priority first, then oldest first. */
    List<Deferred> deferred(String topic, int max);

    /** Claims a waiting task: true for exactly one caller, the one that may dispatch it. */
    boolean claim(String stepExecutionId);

    long deferredCount();
}
