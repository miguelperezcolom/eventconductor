package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.mateu.workflow.application.out.LockService.Grant;
import io.mateu.workflow.application.out.LockService.Outcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcLockService} on PostgreSQL, where it is deployed. The H2 suite
 * ({@code LockServiceJpaE2eTest}) proves the queue discipline; what only PostgreSQL can show is the
 * part written for it: two pods racing the first INSERT of a fresh key, where the loser's duplicate
 * key would abort its whole transaction (25P02) were it not run under a savepoint; the
 * {@code FOR UPDATE} that serialises pods on one key; and the query timeout that bounds a wait on
 * a stuck holder. Skipped without Docker.
 */
class JdbcLockServicePostgresTest {

    private static HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcLockService locks;

    @BeforeAll
    static void database() {
        dataSource = PostgresTestDatabase.freshMigratedDatabase();
    }

    @AfterAll
    static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void service() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM process_lock_waiter");
        jdbc.update("DELETE FROM process_lock");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        locks = newService();
    }

    /** Another pod: its own service over the same database. */
    private JdbcLockService newService() {
        var service = new JdbcLockService(jdbc, tx);
        service.waitTimeoutSeconds = 10;
        service.leaseMs = 900_000;
        return service;
    }

    @Test
    void acquireQueuesAndHandsOverInArrivalOrder() {
        assertThat(locks.acquire("booking", "B-1", "p1", "s1")).isEqualTo(Outcome.ACQUIRED);
        assertThat(locks.acquire("booking", "B-1", "p1", "s1")).as("reentrant").isEqualTo(Outcome.ACQUIRED);
        assertThat(locks.acquire("booking", "B-1", "p2", "s2")).isEqualTo(Outcome.ENQUEUED);
        assertThat(locks.acquire("booking", "B-1", "p2", "s2")).as("queued once").isEqualTo(Outcome.ENQUEUED);
        assertThat(locks.acquire("booking", "B-1", "p3", "s3")).isEqualTo(Outcome.ENQUEUED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_lock_waiter", Integer.class)).isEqualTo(2);

        assertThat(locks.release("booking", "B-1", "p1")).contains(new Grant("booking", "B-1", "p2", "s2"));
        assertThat(locks.release("booking", "B-1", "p1")).as("not the holder any more").isEmpty();
        assertThat(locks.release("booking", "B-1", "p2")).contains(new Grant("booking", "B-1", "p3", "s3"));
        assertThat(locks.release("booking", "B-1", "p3")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_lock", Integer.class)).isZero();
    }

    @Test
    void tryAcquireNeverQueues() {
        assertThat(locks.tryAcquire("booking", "B-2", "p1")).isEqualTo(Outcome.ACQUIRED);
        assertThat(locks.tryAcquire("booking", "B-2", "p2")).isEqualTo(Outcome.BUSY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_lock_waiter", Integer.class)).isZero();
    }

    @Test
    void theRowIdIsPrintableSoPostgresAcceptsIt() {
        assertThat(locks.acquire("reservation", "MRU01/ABC123", "p1", null)).isEqualTo(Outcome.ACQUIRED);
        assertThat(jdbc.queryForObject("SELECT id FROM process_lock", String.class))
                .isEqualTo("11:reservation:MRU01/ABC123");
    }

    /**
     * The race the savepoint exists for, made deterministic: pod A inserts the fresh key's row and
     * holds its transaction open; pod B's INSERT of the same key blocks on it, then fails on the
     * duplicate once A commits. B must roll back to its savepoint, retry on the FOR UPDATE path, find
     * A holding, enqueue — and its own transaction must still be usable afterwards.
     */
    @Test
    void theLoserOfTheFirstInsertEnqueuesAndItsTransactionSurvives() throws Exception {
        var aInserted = new CountDownLatch(1);
        var aMayCommit = new CountDownLatch(1);
        var podA = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            var outcome = locks.acquire("booking", "B-3", "pA", null);
            aInserted.countDown();
            await(aMayCommit);
            return outcome;
        }));
        assertThat(aInserted.await(10, TimeUnit.SECONDS)).isTrue();

        var podBService = newService();
        var podB = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            var outcome = podBService.acquire("booking", "B-3", "pB", null);
            // Still a live transaction: on an aborted one this would fail with 25P02.
            var waiters = jdbc.queryForObject("SELECT COUNT(*) FROM process_lock_waiter", Integer.class);
            return outcome + "/" + waiters;
        }));
        // B is now blocked on A's uncommitted row.
        Thread.sleep(500);
        assertThat(podB).isNotDone();
        aMayCommit.countDown();

        assertThat(podA.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.ACQUIRED);
        assertThat(podB.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.ENQUEUED + "/1");
        assertThat(locks.release("booking", "B-3", "pA")).map(Grant::processId).contains("pB");
    }

    @Test
    void aWaitOnAStuckHolderIsBoundedByTheQueryTimeout() throws Exception {
        locks.acquire("booking", "B-4", "p1", null);
        var holding = new CountDownLatch(1);
        var letGo = new CountDownLatch(1);
        // A pod mid-release: it has the row FOR UPDATE and does not let go.
        var stuck = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT holder_process_id FROM process_lock WHERE id = ? FOR UPDATE",
                    LockRowId.of("booking", "B-4"));
            holding.countDown();
            await(letGo);
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        var impatient = newService();
        impatient.waitTimeoutSeconds = 1;
        try {
            var started = System.nanoTime();
            assertThatThrownBy(() -> impatient.acquire("booking", "B-4", "p2", null))
                    .isInstanceOf(DataAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        } finally {
            letGo.countDown();
            stuck.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void releaseAllFreesEverythingAndLeavesEveryQueue() {
        locks.acquire("booking", "B-5", "p1", null);
        locks.acquire("payment", "B-5", "p1", null);
        locks.acquire("booking", "B-5", "p2", "s2");
        locks.acquire("refund", "R-1", "p3", null);
        locks.acquire("refund", "R-1", "p1", null); // p1 also waits on another process's lock

        var grants = locks.releaseAll("p1");

        assertThat(grants).containsExactly(new Grant("booking", "B-5", "p2", "s2"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_lock_waiter WHERE process_id = 'p1'",
                Integer.class)).isZero();
        assertThat(locks.tryAcquire("payment", "B-5", "p4")).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void anExpiredLeaseIsHandedToTheNextWaiterAndALiveOneIsLeftAlone() {
        locks.acquire("booking", "B-6", "crashed", null);
        locks.acquire("booking", "B-6", "next", "s-next");
        locks.acquire("booking", "B-7", "alive", null);
        jdbc.update("UPDATE process_lock SET lease_deadline_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), LockRowId.of("booking", "B-6"));

        var grants = locks.expireLeases(LocalDateTime.now());

        assertThat(grants).containsExactly(new Grant("booking", "B-6", "next", "s-next"));
        assertThat(jdbc.queryForObject("SELECT holder_process_id FROM process_lock WHERE id = ?",
                String.class, LockRowId.of("booking", "B-7"))).isEqualTo("alive");
        // Nobody waits for an expired lock with an empty queue: it is simply freed.
        jdbc.update("UPDATE process_lock SET lease_deadline_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), LockRowId.of("booking", "B-7"));
        assertThat(locks.expireLeases(LocalDateTime.now())).isEmpty();
        assertThat(locks.tryAcquire("booking", "B-7", "newcomer")).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void theLegacyIdRewriteFindsNothingToDoOnPostgres() {
        // PostgreSQL never accepted a NUL-joined id, so there is nothing to rewrite — but the scan
        // runs before the first operation of every instance and must be harmless here.
        locks.acquire("booking", "B-8", "p1", null);
        assertThat(locks.rewriteLegacyRowIds()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
