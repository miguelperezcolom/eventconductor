package io.mateu.workflow.worker;

import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CancelledTasksTest {

    @Test
    void aCancellationThatArrivesFirstIsRememberedUntilItsTaskShowsUp() throws Exception {
        // The cancellation and the task are different messages on different partitions: arriving
        // second says nothing about which happened first, and a worker that only listens while
        // working misses every cancellation that overtakes its task.
        var cancelled = new CancelledTasks();

        cancelled.accept(new TaskCancellationRequested("se-1"));

        assertThat(cancelled.claim("se-1")).isTrue();
    }

    @Test
    void aClaimConsumesTheCancellationSoItCannotFireTwice() {
        var cancelled = new CancelledTasks();
        cancelled.cancel("se-1");

        assertThat(cancelled.claim("se-1")).isTrue();
        assertThat(cancelled.claim("se-1")).isFalse();
    }

    @Test
    void anUncancelledTaskIsNeverClaimed() {
        assertThat(new CancelledTasks().claim("se-1")).isFalse();
    }

    @Test
    void theSignalFiresForItsOwnTaskOnly() throws Exception {
        var cancelled = new CancelledTasks();
        var fired = new CompletableFuture<String>();
        cancelled.when("se-1").subscribe(fired::complete);

        cancelled.cancel("se-other");
        assertThat(fired).isNotCompleted();

        cancelled.cancel("se-1");
        assertThat(fired.get(1, TimeUnit.SECONDS)).isEqualTo("se-1");
    }

    @Test
    void theSignalDoesNotFireOnItsOwn() {
        // This is what makes it safe as the "other" of a takeUntilOther: it only ever cuts work
        // short on purpose. One that completed by itself would silently abandon every task.
        var completed = new AtomicBoolean();
        new CancelledTasks().when("se-1").subscribe(value -> { }, error -> { }, () -> completed.set(true));

        assertThat(completed).isFalse();
    }

    @Test
    void theMemoryOfUnmatchedCancellationsIsBounded() {
        // A worker that never sees the tasks for the cancellations it receives would otherwise
        // grow one entry per cancellation, for the life of the process.
        var cancelled = new CancelledTasks();
        for (int i = 0; i < CancelledTasks.REMEMBERED + 10; i++) {
            cancelled.cancel("se-" + i);
        }

        assertThat(cancelled.claim("se-0")).isFalse();
        assertThat(cancelled.claim("se-" + (CancelledTasks.REMEMBERED + 9))).isTrue();
    }
}
