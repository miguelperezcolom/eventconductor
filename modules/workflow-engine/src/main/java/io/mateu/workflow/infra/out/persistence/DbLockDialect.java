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
