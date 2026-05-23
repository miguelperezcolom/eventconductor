package io.mateu.workflow.infra.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;

public interface DbLockDialect {
    /** Returns true if the lock was acquired, false if already held by another session. */
    boolean tryLock(Connection con, long lockId) throws SQLException;
    void unlock(Connection con, long lockId) throws SQLException;
}
