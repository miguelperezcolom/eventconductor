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
}
