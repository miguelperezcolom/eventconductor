package io.mateu.workflow.application.httpworkers;

import io.mateu.workflow.dtos.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link HttpTaskQueue} must do — the memory one, and the JDBC one on H2 and PostgreSQL.
 */
public abstract class HttpTaskQueueContract {

    protected static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 18, 12, 0, 0);

    protected HttpTaskQueue queue;

    protected abstract HttpTaskQueue newQueue();

    @BeforeEach
    void freshQueue() {
        queue = newQueue();
    }

    protected static HttpTask task(String id, String topic, int attempt, LocalDateTime createdAt) {
        return HttpTask.queued(id, "p-" + id, "wd", "step-" + id, "", topic,
                List.of(new Variable("amount", "42"), new Variable("note", null)), attempt, createdAt);
    }

    @Test
    void aQueuedTaskIsClaimedWithItsVariablesAndALease() {
        assertThat(queue.offer(task("t1", "payments", 1, T0))).isTrue();

        var claimed = queue.claim(List.of("payments"), 10, "w1", T0.plusSeconds(1), T0.plusSeconds(61));

        assertThat(claimed).hasSize(1);
        var task = claimed.get(0);
        assertThat(task.id()).isEqualTo("t1");
        assertThat(task.processId()).isEqualTo("p-t1");
        assertThat(task.stepId()).isEqualTo("step-t1");
        assertThat(task.attempt()).isEqualTo(1);
        assertThat(task.variables()).containsExactly(new Variable("amount", "42"), new Variable("note", null));
        assertThat(task.workerId()).isEqualTo("w1");
        assertThat(task.leaseToken()).isNotBlank();
        assertThat(task.leaseUntil()).isEqualTo(T0.plusSeconds(61));
        assertThat(task.deliveries()).isEqualTo(1);
        assertThat(queue.find("t1")).contains(task);
    }

    @Test
    void aLeasedTaskIsNotClaimedAgainUntilItsLeaseExpires() {
        queue.offer(task("t1", "payments", 1, T0));
        var first = queue.claim(List.of("payments"), 10, "w1", T0, T0.plusSeconds(60)).get(0);

        assertThat(queue.claim(List.of("payments"), 10, "w2", T0.plusSeconds(59), T0.plusSeconds(120))).isEmpty();

        var redelivered = queue.claim(List.of("payments"), 10, "w2", T0.plusSeconds(61), T0.plusSeconds(121));
        assertThat(redelivered).hasSize(1);
        assertThat(redelivered.get(0).workerId()).isEqualTo("w2");
        assertThat(redelivered.get(0).deliveries()).isEqualTo(2);
        assertThat(redelivered.get(0).leaseToken()).isNotEqualTo(first.leaseToken());
    }

    @Test
    void claimsOnlyTheAskedTopicsOldestFirstUpToTheLimit() {
        queue.offer(task("new", "payments", 1, T0.plusSeconds(3)));
        queue.offer(task("old", "payments", 1, T0.plusSeconds(1)));
        queue.offer(task("other", "shipping", 1, T0));
        queue.offer(task("mid", "billing", 1, T0.plusSeconds(2)));

        var claimed = queue.claim(List.of("payments", "billing"), 2, "w1", T0.plusSeconds(10), T0.plusSeconds(70));

        assertThat(claimed).extracting(HttpTask::id).containsExactly("old", "mid");
        assertThat(queue.claim(Set.of("payments", "billing"), 5, "w1", T0.plusSeconds(10), T0.plusSeconds(70)))
                .extracting(HttpTask::id).containsExactly("new");
    }

    @Test
    void higherPriorityIsClaimedFirstThenOldestFirst() {
        queue.offer(task("old-normal", "payments", 1, T0));
        queue.offer(task("new-urgent", "payments", 1, T0.plusSeconds(5)).withPriority(90));
        queue.offer(task("older-urgent", "payments", 1, T0.plusSeconds(2)).withPriority(90));
        queue.offer(task("low", "payments", 1, T0.minusSeconds(5)).withPriority(10));

        var claimed = queue.claim(List.of("payments"), 3, "w1", T0.plusSeconds(10), T0.plusSeconds(70));

        assertThat(claimed).extracting(HttpTask::id).containsExactly("older-urgent", "new-urgent", "old-normal");
        assertThat(claimed.get(0).priority()).isEqualTo(90);
        assertThat(queue.claim(List.of("payments"), 3, "w1", T0.plusSeconds(10), T0.plusSeconds(70)))
                .extracting(HttpTask::id).containsExactly("low");
    }

    @Test
    void aNewerDispatchReplacesTheTaskAndItsLeaseButADuplicateChangesNothing() {
        queue.offer(task("t1", "payments", 1, T0));
        queue.claim(List.of("payments"), 1, "w1", T0, T0.plusSeconds(60));

        assertThat(queue.offer(task("t1", "payments", 1, T0.plusSeconds(1)))).isFalse();
        assertThat(queue.find("t1").orElseThrow().workerId()).isEqualTo("w1");

        assertThat(queue.offer(task("t1", "payments", 2, T0.plusSeconds(2)))).isTrue();
        var replaced = queue.find("t1").orElseThrow();
        assertThat(replaced.attempt()).isEqualTo(2);
        assertThat(replaced.leaseUntil()).isNull();
        assertThat(replaced.deliveries()).isZero();
        assertThat(queue.claim(List.of("payments"), 1, "w2", T0.plusSeconds(3), T0.plusSeconds(63))).hasSize(1);

        assertThat(queue.offer(task("t1", "payments", 1, T0.plusSeconds(4)))).isFalse();
    }

    @Test
    void aLeaseIsExtendedOnlyByItsHolder() {
        queue.offer(task("t1", "payments", 1, T0));
        var leased = queue.claim(List.of("payments"), 1, "w1", T0, T0.plusSeconds(60)).get(0);

        assertThat(queue.extend("t1", "someone-else", T0.plusSeconds(500))).isFalse();
        assertThat(queue.extend("t1", leased.leaseToken(), T0.plusSeconds(500))).isTrue();
        assertThat(queue.find("t1").orElseThrow().leaseUntil()).isEqualTo(T0.plusSeconds(500));
        assertThat(queue.extend("missing", leased.leaseToken(), T0.plusSeconds(500))).isFalse();
    }

    @Test
    void aReleasedLeaseMakesTheTaskAvailableAtOnce() {
        queue.offer(task("t1", "payments", 1, T0));
        var leased = queue.claim(List.of("payments"), 1, "w1", T0, T0.plusSeconds(60)).get(0);

        queue.release("t1", "not-the-token");
        assertThat(queue.claim(List.of("payments"), 1, "w2", T0.plusSeconds(1), T0.plusSeconds(61))).isEmpty();

        queue.release("t1", leased.leaseToken());
        assertThat(queue.claim(List.of("payments"), 1, "w2", T0.plusSeconds(1), T0.plusSeconds(61))).hasSize(1);
    }

    @Test
    void removeTakesOnlyTheDispatchItWasAskedFor() {
        queue.offer(task("t1", "payments", 2, T0));

        assertThat(queue.remove("t1", 1)).isFalse();
        assertThat(queue.find("t1")).isPresent();
        assertThat(queue.remove("t1", 2)).isTrue();
        assertThat(queue.find("t1")).isEmpty();
        assertThat(queue.remove("t1", null)).isFalse();

        queue.offer(task("t2", "payments", 1, T0));
        assertThat(queue.remove("t2", null)).isTrue();
    }

    @Test
    void cancellingAnUnheldTaskRemovesItAndAHeldOneIsFlagged() {
        queue.offer(task("free", "payments", 1, T0));
        queue.offer(task("held", "payments", 1, T0.plusSeconds(1)));
        queue.claim(List.of("payments"), 1, "w1", T0.plusSeconds(2), T0.plusSeconds(60));
        // "free" was the oldest, so it is the one held now; "held" is still free.

        assertThat(queue.cancel("held", T0.plusSeconds(3))).isEqualTo(HttpTaskQueue.CancelOutcome.REMOVED);
        assertThat(queue.find("held")).isEmpty();

        assertThat(queue.cancel("free", T0.plusSeconds(3))).isEqualTo(HttpTaskQueue.CancelOutcome.FLAGGED);
        var flagged = queue.find("free").orElseThrow();
        assertThat(flagged.cancelled()).isTrue();
        assertThat(queue.extend("free", flagged.leaseToken(), T0.plusSeconds(100))).isFalse();
        assertThat(queue.claim(List.of("payments"), 5, "w2", T0.plusSeconds(500), T0.plusSeconds(560))).isEmpty();

        assertThat(queue.cancel("missing", T0)).isEqualTo(HttpTaskQueue.CancelOutcome.ABSENT);
    }

    @Test
    void purgeDropsExpiredCancelledTasksAndOrphans() {
        queue.offer(task("cancelled", "payments", 1, T0.plusDays(1)));
        queue.claim(List.of("payments"), 1, "w1", T0.plusDays(1), T0.plusDays(1).plusSeconds(60));
        queue.cancel("cancelled", T0.plusDays(1));
        queue.offer(task("orphan", "payments", 1, T0));
        queue.offer(task("live", "payments", 1, T0.plusDays(1)));

        var now = T0.plusDays(1).plusSeconds(30);
        assertThat(queue.purge(now, T0.plusHours(1))).isEqualTo(1); // the orphan; the cancelled one is still leased
        assertThat(queue.find("orphan")).isEmpty();

        var later = T0.plusDays(1).plusSeconds(120);
        assertThat(queue.purge(later, T0.plusHours(1))).isEqualTo(1);
        assertThat(queue.find("cancelled")).isEmpty();
        assertThat(queue.find("live")).isPresent();
    }

    @Test
    void countsTellAvailableFromLeased() {
        queue.offer(task("a", "payments", 1, T0));
        queue.offer(task("b", "payments", 1, T0.plusSeconds(1)));
        queue.offer(task("c", "payments", 1, T0.plusSeconds(2)));
        queue.claim(List.of("payments"), 1, "w1", T0, T0.plusSeconds(60));

        assertThat(queue.counts(T0.plusSeconds(10))).isEqualTo(new HttpTaskQueue.Counts(2, 1));
        assertThat(queue.counts(T0.plusSeconds(100))).isEqualTo(new HttpTaskQueue.Counts(3, 0));
    }

    @Test
    void anEmptyAskClaimsNothing() {
        queue.offer(task("a", "payments", 1, T0));
        assertThat(queue.claim(List.of(), 5, "w1", T0, T0.plusSeconds(60))).isEmpty();
        assertThat(queue.claim(List.of("payments"), 0, "w1", T0, T0.plusSeconds(60))).isEmpty();
    }
}
