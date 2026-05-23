package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database-agnostic advisory-lock implementation of ProcessLockService.
 * The actual locking SQL is delegated to DbLockDialect, which is auto-detected
 * from the JDBC connection metadata (PostgreSQL, MariaDB/MySQL, Oracle).
 *
 * The connection that acquires the lock is held in a map until unlock() is called,
 * guaranteeing that both operations run on the same session (advisory locks are
 * session-scoped in all supported databases).
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@RequiredArgsConstructor
public class JdbcProcessLockService implements ProcessLockService {

    private final DataSource dataSource;
    private final DbLockDialect dialect;
    private final ConcurrentHashMap<Long, Connection> heldConnections = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String processId) {
        long key = toLockKey(processId);
        try {
            Connection con = dataSource.getConnection();
            if (dialect.tryLock(con, key)) {
                heldConnections.put(key, con);
                return true;
            }
            con.close();
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Error acquiring lock for process " + processId, e);
        }
    }

    @Override
    public void unlock(String processId) {
        long key = toLockKey(processId);
        Connection con = heldConnections.remove(key);
        if (con == null) return;
        try {
            dialect.unlock(con, key);
        } catch (Exception e) {
            throw new RuntimeException("Error releasing lock for process " + processId, e);
        } finally {
            try { con.close(); } catch (Exception ignored) {}
        }
    }

    static long toLockKey(String processId) {
        UUID uuid = UUID.fromString(processId);
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }
}
