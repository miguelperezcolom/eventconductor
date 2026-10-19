package io.mateu.workflow.infra.in.scheduler;

/**
 * A periodic engine job that can also be run once, now, on demand.
 *
 * <p>Every scheduler the engine runs in the background — the deadline scan (step timeouts, TIMER
 * steps, retry backoffs), cron starts, the retention purges, the lock-lease reaper, the stalled
 * reports, the analytics rollup — implements this, so that something other than the passage of real
 * time can drive it: the {@code workflow-test} kit moves its clock and then runs every pass, which
 * is how a test skips three days without waiting them.
 *
 * <p>{@link #runOnce()} reads the time from {@link io.mateu.workflow.time.EngineClock}, exactly as the
 * background loop does; it does not take a lock of its own that the loop would not take. With
 * {@code workflow.schedulers.background=false} the loops are not started at all and these passes are
 * the only way the jobs run (for tests; production leaves it at its default, {@code true}).
 */
public interface SchedulerPass {

    /** Property that, set to {@code false}, keeps every scheduler's background loop from starting. */
    String BACKGROUND_PROPERTY = "workflow.schedulers.background";

    /**
     * Runs one pass at the engine clock's current time, on the calling thread.
     *
     * @return how many things the pass acted on (processes signalled, processes started, rows
     *         purged …); zero when nothing was due. A hint for a caller looping until idle, not a
     *         contract on what was done.
     */
    int runOnce();

    /**
     * The next moment this pass has something to do, when it knows — so the test kit's {@code advanceTime}
     * stops there instead of jumping over it. Empty (the default) for a pass the kit learns about otherwise.
     */
    default java.util.Optional<java.time.Instant> nextDueAt() {
        return java.util.Optional.empty();
    }
}
