package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.LockService.Grant;
import io.mateu.workflow.application.out.LockService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock contract, pinned on the in-heap implementation: acquire/enqueue, FIFO admission,
 * reentrancy, release-to-next, and the terminal-state {@code releaseAll} sweep. The JDBC
 * implementation is expected to behave identically and is exercised end to end in the JPA suite.
 */
class InMemoryLockServiceTest {

    private static final String LOCK = "booking";
    private static final String KEY = "B-1";

    private InMemoryLockService locks;

    @BeforeEach
    void setUp() {
        locks = new InMemoryLockService();
    }

    @Test
    void first_acquirer_takes_the_lock() {
        assertThat(locks.acquire(LOCK, KEY, "p1", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void second_acquirer_of_a_held_key_is_enqueued() {
        locks.acquire(LOCK, KEY, "p1", null);
        assertThat(locks.acquire(LOCK, KEY, "p2", null)).isEqualTo(Outcome.ENQUEUED);
    }

    @Test
    void acquisition_is_reentrant_for_the_holder() {
        locks.acquire(LOCK, KEY, "p1", null);
        assertThat(locks.acquire(LOCK, KEY, "p1", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void release_admits_the_earliest_waiter_first() {
        locks.acquire(LOCK, KEY, "p1", null);
        locks.acquire(LOCK, KEY, "p2", null);
        locks.acquire(LOCK, KEY, "p3", null);

        Optional<Grant> granted = locks.release(LOCK, KEY, "p1");

        assertThat(granted).map(Grant::processId).contains("p2");
        // p2 now holds it; p3 is still queued, p2 is not re-enqueued.
        assertThat(locks.acquire(LOCK, KEY, "p4", null)).isEqualTo(Outcome.ENQUEUED);
        assertThat(locks.release(LOCK, KEY, "p2")).map(Grant::processId).contains("p3");
        assertThat(locks.release(LOCK, KEY, "p3")).map(Grant::processId).contains("p4");
    }

    @Test
    void release_with_no_waiters_frees_the_key() {
        locks.acquire(LOCK, KEY, "p1", null);

        assertThat(locks.release(LOCK, KEY, "p1")).isEmpty();
        // Free again: the next acquirer takes it outright rather than queueing.
        assertThat(locks.acquire(LOCK, KEY, "p2", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void release_by_a_non_holder_is_a_no_op() {
        locks.acquire(LOCK, KEY, "p1", null);
        locks.acquire(LOCK, KEY, "p2", null);

        assertThat(locks.release(LOCK, KEY, "p2")).isEmpty(); // p2 only waits, it does not hold
        // p1 still holds it; releasing legitimately still admits p2.
        assertThat(locks.release(LOCK, KEY, "p1")).map(Grant::processId).contains("p2");
    }

    @Test
    void enqueue_is_idempotent_for_a_waiting_process() {
        locks.acquire(LOCK, KEY, "p1", null);
        locks.acquire(LOCK, KEY, "p2", null);
        locks.acquire(LOCK, KEY, "p2", null); // same process asks again while blocked

        locks.release(LOCK, KEY, "p1"); // -> p2
        // If p2 had been queued twice, this release would hand it back to p2 again.
        assertThat(locks.release(LOCK, KEY, "p2")).isEmpty();
    }

    @Test
    void different_keys_do_not_contend() {
        assertThat(locks.acquire(LOCK, "B-1", "p1", null)).isEqualTo(Outcome.ACQUIRED);
        assertThat(locks.acquire(LOCK, "B-2", "p2", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void different_lock_names_do_not_contend() {
        assertThat(locks.acquire("booking", KEY, "p1", null)).isEqualTo(Outcome.ACQUIRED);
        assertThat(locks.acquire("payment", KEY, "p2", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void releaseAll_frees_every_held_lock_and_returns_grants() {
        locks.acquire("booking", "B-1", "p1", null);
        locks.acquire("payment", "B-1", "p1", null);
        // p2 waits behind p1 on the booking lock only.
        locks.acquire("booking", "B-1", "p2", null);

        List<Grant> grants = locks.releaseAll("p1");

        assertThat(grants).extracting(Grant::processId).containsExactly("p2");
        // The payment lock had no waiter, so it is simply free now.
        assertThat(locks.acquire("payment", "B-1", "p3", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void an_expired_lease_with_a_waiter_admits_the_waiter() {
        locks.leaseMs = 1;
        locks.acquire(LOCK, KEY, "p1", null);
        locks.acquire(LOCK, KEY, "p2", null);

        var grants = locks.expireLeases(LocalDateTime.now().plusHours(1));

        assertThat(grants).extracting(Grant::processId).containsExactly("p2");
        // p2 holds it now; p1 (the evicted holder) is gone from the picture.
        assertThat(locks.release(LOCK, KEY, "p2")).isEmpty();
    }

    @Test
    void an_expired_lease_with_no_waiter_frees_the_key() {
        locks.leaseMs = 1;
        locks.acquire(LOCK, KEY, "p1", null);

        assertThat(locks.expireLeases(LocalDateTime.now().plusHours(1))).isEmpty();
        // Free again: the next acquirer takes it outright.
        assertThat(locks.acquire(LOCK, KEY, "p2", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void a_lease_that_has_not_expired_is_left_alone() {
        locks.leaseMs = 900_000;
        locks.acquire(LOCK, KEY, "p1", null);

        assertThat(locks.expireLeases(LocalDateTime.now())).isEmpty();
        // p1 still holds it: a newcomer queues.
        assertThat(locks.acquire(LOCK, KEY, "p2", null)).isEqualTo(Outcome.ENQUEUED);
    }

    @Test
    void releaseAll_drops_the_process_from_queues_it_only_waits_in() {
        locks.acquire(LOCK, KEY, "p1", null);
        locks.acquire(LOCK, KEY, "p2", null); // p2 only waits

        locks.releaseAll("p2");

        // With p2 gone from the queue, releasing p1 frees the key rather than handing it to p2.
        assertThat(locks.release(LOCK, KEY, "p1")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void tryAcquireTakesAFreeLockAndNeverQueues() {
        assertThat(locks.tryAcquire("booking", "B1", "p1")).isEqualTo(io.mateu.workflow.application.out.LockService.Outcome.ACQUIRED);
        assertThat(locks.tryAcquire("booking", "B1", "p1")).as("reentrant").isEqualTo(io.mateu.workflow.application.out.LockService.Outcome.ACQUIRED);
        assertThat(locks.tryAcquire("booking", "B1", "p2")).isEqualTo(io.mateu.workflow.application.out.LockService.Outcome.BUSY);
        // p2 was not queued: releasing p1's hold admits nobody.
        assertThat(locks.release("booking", "B1", "p1")).isEmpty();
        assertThat(locks.tryAcquire("booking", "B1", "p2")).isEqualTo(io.mateu.workflow.application.out.LockService.Outcome.ACQUIRED);
    }
}
