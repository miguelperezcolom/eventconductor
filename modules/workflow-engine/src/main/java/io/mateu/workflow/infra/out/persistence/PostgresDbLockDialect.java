package io.mateu.workflow.infra.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;

public class PostgresDbLockDialect implements DbLockDialect {

    @Override
    public boolean tryLock(Connection con, long lockId) throws SQLException {
        try (var ps = con.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, lockId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    @Override
    public void unlock(Connection con, long lockId) throws SQLException {
        try (var ps = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockId);
            ps.execute();
        }
    }

    @Override
    public String claimPendingOutboxSql() {
        return "SELECT id FROM outbox_message_entity WHERE status = 'Pending' "
                + "ORDER BY timestamp LIMIT ? FOR UPDATE SKIP LOCKED";
    }

    /** Same id the relay's leader lock used to have, so the chaos tests keep working unchanged. */
    private static final long RELAY_GATE_ID = 111222333L;

    @Override
    public boolean tryRelayGate(Connection con) throws SQLException {
        try (var ps = con.prepareStatement("SELECT pg_try_advisory_lock_shared(?)")) {
            ps.setLong(1, RELAY_GATE_ID);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    @Override
    public void releaseRelayGate(Connection con) throws SQLException {
        try (var ps = con.prepareStatement("SELECT pg_advisory_unlock_shared(?)")) {
            ps.setLong(1, RELAY_GATE_ID);
            ps.execute();
        }
    }
}
