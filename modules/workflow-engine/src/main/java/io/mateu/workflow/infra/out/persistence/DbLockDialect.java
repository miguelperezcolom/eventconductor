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
     * <p>The limit has to be inside the statement: bounding the result set client-side (JDBC
     * {@code setMaxRows}) still lets the server lock every matching row, which would leave the
     * other pods with nothing to claim — the very thing this replaced.
     */
    String claimPendingOutboxSql();

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
