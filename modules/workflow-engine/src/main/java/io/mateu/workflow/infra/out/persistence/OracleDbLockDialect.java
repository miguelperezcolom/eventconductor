package io.mateu.workflow.infra.out.persistence;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Uses Oracle DBMS_LOCK for session-scoped advisory locks equivalent to PostgreSQL's pg_advisory_lock.
 * Requires EXECUTE privilege on DBMS_LOCK (grant by DBA: GRANT EXECUTE ON DBMS_LOCK TO <user>).
 * The lock name is the string representation of the long ID.
 * release_on_commit => FALSE means the lock persists until explicitly released or the session ends.
 */
public class OracleDbLockDialect implements DbLockDialect {

    @Override
    public boolean tryLock(Connection con, long lockId) throws SQLException {
        try (CallableStatement cs = con.prepareCall(
                "DECLARE l_h VARCHAR2(128); BEGIN " +
                "DBMS_LOCK.ALLOCATE_UNIQUE(?, l_h); " +
                "? := DBMS_LOCK.REQUEST(l_h, DBMS_LOCK.X_MODE, 0, FALSE); " +
                "END;")) {
            cs.setString(1, String.valueOf(lockId));
            cs.registerOutParameter(2, Types.INTEGER);
            cs.execute();
            return cs.getInt(2) == 0; // 0 = acquired, 1 = timeout (already held)
        }
    }

    @Override
    public void unlock(Connection con, long lockId) throws SQLException {
        try (CallableStatement cs = con.prepareCall(
                "DECLARE l_h VARCHAR2(128); BEGIN " +
                "DBMS_LOCK.ALLOCATE_UNIQUE(?, l_h); " +
                "DBMS_LOCK.RELEASE(l_h); " +
                "END;")) {
            cs.setString(1, String.valueOf(lockId));
            cs.execute();
        }
    }

    /**
     * Oracle rejects a row-limiting clause together with {@code FOR UPDATE} (ORA-02014), so the
     * limit is applied in a {@code ROWNUM} subquery — which also keeps the ordering that a bare
     * {@code ROWNUM} predicate would lose, since it is evaluated before {@code ORDER BY}.
     */
    @Override
    public String claimPendingOutboxSql() {
        return "SELECT id FROM outbox_message_entity WHERE id IN ("
                + "SELECT id FROM (SELECT o.id FROM outbox_message_entity o WHERE o.status = 'Pending' AND "
                + HEAD_OF_KEY + " ORDER BY o.timestamp) WHERE ROWNUM <= ?) FOR UPDATE SKIP LOCKED";
    }

    /** The followers, in the same {@code ROWNUM}-outside-the-sort shape and without a row-limiting clause. */
    @Override
    public String claimFollowingOutboxSql(int keyCount) {
        return "SELECT id FROM outbox_message_entity WHERE id IN ("
                + "SELECT id FROM (SELECT o.id FROM outbox_message_entity o WHERE o.status = 'Pending' "
                + "AND o.partition_key IN (" + DbLockDialect.placeholders(keyCount) + ") "
                + "ORDER BY o.timestamp, o.id) WHERE ROWNUM <= ?) FOR UPDATE";
    }

    /** No {@code LIMIT} here either — same {@code ROWNUM}-outside-the-sort shape as the claim. */
    @Override
    public String selectSentOutboxToPurgeSql() {
        return "SELECT id FROM (SELECT id FROM outbox_message_entity "
                + "WHERE status = 'Sent' AND timestamp < ? ORDER BY timestamp) WHERE ROWNUM <= ?";
    }
}
