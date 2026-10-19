package io.mateu.workflow.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The engine's one source of <em>business time</em>: every moment the engine records on a process
 * or a step, or compares against one (a start, a finish, a TIMER's due moment, a timeout deadline,
 * a retry's backoff, a cron occurrence, a retention cut-off), is read here rather than from
 * {@code LocalDateTime.now()}.
 *
 * <p>In production it is {@link Clock#systemDefaultZone()} — the clock the engine always read, in
 * the zone its {@code LocalDateTime} columns have always been in — so nothing changes. The Spring
 * context exposes the same clock as the {@code eventconductorClock} bean and installs it here when
 * it starts; the test kit ({@code workflow-test}) installs a clock it can move, which is how a test
 * waits three days in a millisecond.
 *
 * <p>A static holder, not only a bean, because the reads it replaces are in domain objects
 * ({@code StepExecution.start}, {@code ProcessReply.of}) and static helpers that are not Spring
 * beans and should not become ones to be told the time.
 *
 * <p><b>Not business time, and so deliberately not read here:</b> elapsed-time measurement for
 * metrics ({@code System.nanoTime()}), the outbox's delivery claims and leases, a synchronous HTTP
 * caller's real wait, cache TTLs (rules, OAuth tokens) and placement bookkeeping. Those are about
 * threads and sockets waiting in real time, and skipping them would not simulate anything.
 */
public final class EngineClock {

    private static final Clock SYSTEM = Clock.systemDefaultZone();

    private static volatile Clock clock = SYSTEM;

    private EngineClock() {
    }

    /** The clock currently in force. */
    public static Clock get() {
        return clock;
    }

    /** Installs {@code newClock}; {@code null} restores the system clock. */
    public static void set(Clock newClock) {
        clock = newClock == null ? SYSTEM : newClock;
    }

    /** Restores the system clock. */
    public static void reset() {
        clock = SYSTEM;
    }

    /** The system clock in the default zone — what the engine read before this class existed. */
    public static Clock system() {
        return SYSTEM;
    }

    /** {@code LocalDateTime.now()}, from the engine clock. */
    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** {@code Instant.now()}, from the engine clock. */
    public static Instant instant() {
        return clock.instant();
    }

    /** {@code System.currentTimeMillis()}, from the engine clock. */
    public static long millis() {
        return clock.millis();
    }

    /** {@code LocalDate.now()}, from the engine clock. */
    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The zone the engine's {@code LocalDateTime}s are in. */
    public static ZoneId zone() {
        return clock.getZone();
    }
}
