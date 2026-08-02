package io.mateu.workflow.infra.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Uses MariaDB/MySQL named locks (GET_LOCK / RELEASE_LOCK).
 * The long lock ID is converted to its string representation (max 20 chars, well within the 64-char limit).
 * Named locks are connection-scoped and are released automatically when the connection is closed.
 */
public class MariaDbLockDialect implements DbLockDialect {

    @Override
    public boolean tryLock(Connection con, long lockId) throws SQLException {
        try (var ps = con.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            ps.setString(1, String.valueOf(lockId));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    @Override
    public void unlock(Connection con, long lockId) throws SQLException {
        try (var ps = con.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, String.valueOf(lockId));
            ps.execute();
        }
    }

    @Override
    public String claimPendingOutboxSql() {
        return "SELECT id FROM outbox_message_entity WHERE status = 'Pending' "
                + "ORDER BY timestamp LIMIT ? FOR UPDATE SKIP LOCKED";
    }
}
