package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.out.LockService.Grant;
import io.mateu.workflow.application.out.LockService.Outcome;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JDBC {@link LockService} — {@code SELECT … FOR UPDATE}, the insert-and-retry on a
 * fresh key, the FIFO waiter promotion — actually runs on the real schema. It exercises
 * {@code JdbcLockService} against the H2 (PostgreSQL-compatibility) database the JPA suite boots,
 * with the lock tables created by {@code ddl-auto} from the entities, so it catches anything the
 * in-heap contract test cannot: the SQL, the portable DDL, the transaction boundaries.
 *
 * <p>Sequential by design — one waiter at a time — so it asserts admission without depending on the
 * tie-break between two waiters enqueued in the same timestamp tick. Multi-pod contention is P4.
 */
class LockServiceJpaE2eTest extends AbstractJpaE2eTest {

    private static final String LOCK = "booking";
    private static final String KEY = "B-1";

    @Autowired
    LockService locks;

    @Test
    void the_jdbc_lock_serializes_and_admits_fifo_over_the_real_schema() {
        // First acquirer takes it; a second process on the same key is parked.
        assertThat(locks.acquire(LOCK, KEY, "p1", null)).isEqualTo(Outcome.ACQUIRED);
        assertThat(locks.acquire(LOCK, KEY, "p2", null)).isEqualTo(Outcome.ENQUEUED);

        // Reentrant: the holder re-acquiring does not perturb the queue.
        assertThat(locks.acquire(LOCK, KEY, "p1", null)).isEqualTo(Outcome.ACQUIRED);

        // Releasing hands the lock to the waiter.
        Optional<Grant> toP2 = locks.release(LOCK, KEY, "p1");
        assertThat(toP2).map(Grant::processId).contains("p2");

        // A newcomer now queues behind p2, and gets it when p2 releases.
        assertThat(locks.acquire(LOCK, KEY, "p3", null)).isEqualTo(Outcome.ENQUEUED);
        assertThat(locks.release(LOCK, KEY, "p2")).map(Grant::processId).contains("p3");

        // Last holder releases with an empty queue: the key is free again.
        assertThat(locks.release(LOCK, KEY, "p3")).isEmpty();
        assertThat(locks.acquire(LOCK, KEY, "p4", null)).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void releaseAll_frees_every_lock_a_process_holds() {
        locks.acquire("booking", "B-1", "p1", null);
        locks.acquire("payment", "B-1", "p1", null);
        locks.acquire("booking", "B-1", "p2", null); // p2 waits behind p1 on booking

        List<Grant> grants = locks.releaseAll("p1");

        // The booking lock is handed to p2; the payment lock had no waiter and is simply freed.
        assertThat(grants).extracting(Grant::processId).containsExactly("p2");
        assertThat(locks.acquire("payment", "B-1", "p3", null)).isEqualTo(Outcome.ACQUIRED);
    }
}
