package io.mateu.workflow.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} that only moves when it is told to. The kit installs it as the engine clock, so
 * every moment the engine records or compares — a process start, a TIMER's due moment, a timeout
 * deadline, a retry backoff, a cron occurrence — is read from here.
 *
 * <p>It never goes backwards: a test that wants an earlier moment wants a different process, not a
 * clock that undoes what already happened.
 */
public final class TestClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public TestClock(Instant start, ZoneId zone) {
        this.instant = new AtomicReference<>(start);
        this.zone = zone;
    }

    /** Starts at the current wall-clock time (to the millisecond), in the JVM's default zone. */
    public static TestClock startingNow() {
        return new TestClock(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault());
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    /** The current moment as the engine's {@code LocalDateTime}s express it. */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(instant(), zone);
    }

    /** Moves the clock forward by {@code duration} (zero is allowed, negative is not). */
    public Instant advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("The test clock does not go backwards (asked to move " + duration + ")");
        }
        return instant.updateAndGet(current -> current.plus(duration));
    }

    /** Moves the clock to {@code target}; a moment already past is ignored (the clock never goes back). */
    public Instant advanceTo(Instant target) {
        return instant.updateAndGet(current -> target.isAfter(current) ? target : current);
    }
}
