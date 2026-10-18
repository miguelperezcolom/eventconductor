package io.mateu.workflow.infra.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;

public interface DbLockDialect {
    /** Returns true if the lock was acquired, false if already held by another session. */
    boolean tryLock(Connection con, long lockId) throws SQLException;
    void unlock(Connection con, long lockId) throws SQLException;

    /**
     * Selects the ids of at most {@code ?} pending outbox messages, taking a row lock on each and
     * skipping the ones another pod already holds, so several relays drain disjoint slices of the
     * outbox with no leader election between them.
     *
     * <p>Only {@linkplain #HEAD_OF_KEY heads of their partition key} are claimed, so a process's
     * events cannot be split across pods and published out of order; the rest of each claimed run
     * comes from {@link #claimFollowingOutboxSql(int)} in the same transaction.
     *
     * <p>The limit has to be inside the statement: bounding the result set client-side (JDBC
     * {@code setMaxRows}) still lets the server lock every matching row, which would leave the
     * other pods with nothing to claim — the very thing this replaced.
     */
    String claimPendingOutboxSql();

    /**
     * The predicate that makes a pending row the <b>head</b> of its partition key: no other
     * {@code Pending} row of the same key comes before it in {@code (timestamp, id)} order.
     *
     * <p>Every relay claims heads only, so while one pod holds (or keeps failing) the oldest pending
     * event of a process, no other pod can take a newer one of that process and put it on the topic
     * first. {@code SKIP LOCKED} alone gave the opposite: the next pod skipped the locked row and
     * claimed its successor. The id breaks timestamp ties the way {@code OutboxDrain} sorts a batch,
     * so "the head" means one row, never two that two pods could take at once.
     *
     * <p>A row without a key ({@code NULL}: an unkeyed event, or a row written before V33 gave the
     * outbox the column) matches no {@code =}, so it is always a head — ordered against nothing,
     * exactly as before. Only {@code Pending} rows hold their successors back: a {@code Sent} row is
     * on the topic already and an {@code Error} row will never be, which is the same choice the drain
     * makes when it parks a poisoned message and carries on with the rest of its process.
     */
    String HEAD_OF_KEY = "NOT EXISTS (SELECT 1 FROM outbox_message_entity o2 "
            + "WHERE o2.partition_key = o.partition_key AND o2.status = 'Pending' "
            + "AND o2.timestamp <= o.timestamp "
            + "AND (o2.timestamp < o.timestamp OR o2.id < o.id))";

    /**
     * Selects, oldest first, at most {@code ?} pending rows of the given partition keys — the runs
     * whose heads this transaction has just claimed, heads included — and locks them. Parameters:
     * the {@code keyCount} keys, then the limit.
     *
     * <p>Without it a pass could relay only one event per process, and since the relay loops only
     * on a full batch, a process's second event would wait for the next poll. Taking the followers
     * here is what lets one pod relay a whole run in one pass, in order, while no other pod can
     * touch any of it (they are not heads, so no other relay's claim matches them).
     *
     * <p>Ordered by {@code (timestamp, id)} under one limit, so what is taken of each key is a
     * prefix of its run: a cut leaves the tail Pending behind a row that is also still Pending until
     * this transaction commits. <b>No {@code SKIP LOCKED}</b>, deliberately: skipping a row in the
     * middle of a run would send its successor first. Nothing else locks a non-head pending row, so
     * the plain lock does not wait in practice, and the heads are this transaction's already.
     *
     * <p>The heads come back too, rather than being excluded with a second {@code NOT EXISTS}: the
     * caller drops the ids it holds already. Measured on PostgreSQL with one process 10 000 events
     * behind, excluding them in SQL probed every row of the run (70 ms); this is one index range
     * and a top-N sort (2 ms).
     */
    default String claimFollowingOutboxSql(int keyCount) {
        return "SELECT id FROM outbox_message_entity o WHERE o.status = 'Pending' "
                + "AND o.partition_key IN (" + placeholders(keyCount) + ") "
                + "ORDER BY o.timestamp, o.id LIMIT ? FOR UPDATE";
    }

    static String placeholders(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("at least one partition key is required");
        }
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * Selects the ids of at most {@code ?} outbox rows that were sent before {@code ?}, oldest
     * first, so retention can delete them by primary key.
     *
     * <p><b>Only {@code Sent}.</b> That single predicate is the whole safety argument: a Sent row
     * has been accepted by the broker and nothing in the engine ever reads it again, while a
     * Pending row is undelivered work and an Error row is a parked message somebody has to look at.
     * Widening this filter would turn a housekeeping job into silent data loss, so every dialect
     * below repeats it rather than sharing a fragment that could be edited in one place.
     *
     * <p><b>Selecting first is not indirection.</b> The obvious form — one {@code DELETE} whose
     * {@code WHERE id IN (…)} carries the limit — is not reliably bounded, because whether the
     * subquery is evaluated once or per candidate row is the optimiser's business. Measured on H2:
     * asking for two rows deleted one, and asking again deleted one more. A purge that removes a
     * row per pass never catches up with the table it exists to bound, and it would have looked
     * like it was working. Selecting the ids and deleting them by primary key is exactly bounded
     * on every database, and it is the shape {@link #claimPendingOutboxSql()} already uses.
     *
     * <p>Bounded and oldest-first for the same reason the claim is: an unbounded delete on a table
     * with hundreds of millions of rows takes a lock and a transaction long enough to matter to
     * everything else using that database.
     */
    default String selectSentOutboxToPurgeSql() {
        return "SELECT id FROM outbox_message_entity WHERE status = 'Sent' AND timestamp < ? "
                + "ORDER BY timestamp LIMIT ?";
    }

    /**
     * Relay gate. Every relay holds it in <b>shared</b> mode while draining, so pods never block
     * each other, but a single exclusive holder freezes all of them at once — the deterministic
     * "committed a step, never dispatched the next" window the distributed crash tests build on
     * (DIST-02, DIST-08 acquire {@code pg_advisory_lock(111222333)}).
     *
     * <p>Open by default: the gate only has to mean something where those tests run.
     */
    default boolean tryRelayGate(Connection con) throws SQLException {
        return true;
    }

    default void releaseRelayGate(Connection con) throws SQLException {
    }
}
