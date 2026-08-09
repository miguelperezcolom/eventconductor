package io.mateu.workflow.worker;

import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The other half of {@link WorkerReply}: what a worker does when the engine tells it to stop.
 *
 * <p>The engine publishes {@link TaskCancellationRequested} on the same {@code downstream}
 * destination the task itself arrived on — when a step times out, when an operator cancels the
 * process, and (since the same hole was found in both) when a saga rollback or an END transition
 * gives up on a step a worker is still running. Every worker in this repository filtered those
 * events out, so cancellation was a contract the engine kept and nobody listened to: the worker
 * went on and booked the reservation for a saga that had already been rolled back.
 *
 * <p>Two things are needed to honour it, and a worker that only does the obvious one is still
 * broken:
 *
 * <ul>
 *   <li><b>Stop the work that is running.</b> {@link #when(String)} is a signal to hang the work
 *       off, so it is abandoned rather than merely unreported.</li>
 *   <li><b>Remember cancellations that arrive first.</b> The cancellation and the task travel on
 *       different partitions, so the order between them is not guaranteed; a worker that only
 *       listens while working misses the ones that overtake the task. {@link #claim(String)} is
 *       the check to make before starting.</li>
 * </ul>
 *
 * <p>None of this makes cancellation reliable, and it is not meant to: it races with the work, so
 * the reservation may already exist when the event lands. Idempotency in the worker is what makes
 * that survivable — cancellation only narrows the window.
 *
 * <p>The set of remembered ids is bounded: a worker that never sees the tasks for the
 * cancellations it receives would otherwise grow one entry per cancellation, forever.
 */
public final class CancelledTasks {

    /** How many not-yet-matched cancellations to keep. Oldest is dropped first. */
    static final int REMEMBERED = 10_000;

    private final Set<String> cancelled = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            }));

    private final Sinks.Many<String> live = Sinks.many().multicast().directBestEffort();

    /** Records a cancellation received from the engine. */
    public void accept(TaskCancellationRequested event) {
        cancel(event.taskId());
    }

    /**
     * Records a cancellation by task execution id. The id is the engine's step execution id: it
     * travels as {@code taskId} on the cancellation and as {@code taskExecutionId} on the task,
     * which are the same value under two names.
     */
    public void cancel(String taskExecutionId) {
        cancelled.add(taskExecutionId);
        // Best effort by design: with no worker currently on this task there is nobody to wake,
        // and the set above is what makes the cancellation survive until the task shows up.
        live.tryEmitNext(taskExecutionId);
    }

    /**
     * Whether this task was cancelled, consuming the record so it cannot fire twice. Call it
     * before starting a task and again before reporting it done.
     */
    public boolean claim(String taskExecutionId) {
        return cancelled.remove(taskExecutionId);
    }

    /**
     * Completes when this task is cancelled. Never completes otherwise, which is what makes it
     * usable as the "other" of a {@code takeUntilOther}: it only ever cuts work short on purpose.
     */
    public Mono<String> when(String taskExecutionId) {
        return live.asFlux().filter(taskExecutionId::equals).next();
    }
}
