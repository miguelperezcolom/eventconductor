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
 * PostgreSQL advisory-lock implementation of ProcessLockService.
 * Converts the UUID processId to a stable long key via XOR of its two 64-bit halves.
 *
 * The connection that acquires the lock is held in a map until unlock() is called,
 * guaranteeing that both operations run on the same session (advisory locks are
 * session-scoped in PostgreSQL and would leak if acquired and released on different
 * pool connections).
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@RequiredArgsConstructor
public class PostgresProcessLockService implements ProcessLockService {

    private final DataSource dataSource;
    private final ConcurrentHashMap<Long, Connection> heldConnections = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String processId) {
        long key = toLockKey(processId);
        try {
            Connection con = dataSource.getConnection();
            try (var ps = con.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, key);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        heldConnections.put(key, con);
                        return true;
                    }
                }
            }
            con.close();
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Error acquiring advisory lock for process " + processId, e);
        }
    }

    @Override
    public void unlock(String processId) {
        long key = toLockKey(processId);
        Connection con = heldConnections.remove(key);
        if (con == null) return;
        try {
            try (var ps = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, key);
                ps.execute();
            }
        } catch (Exception e) {
            throw new RuntimeException("Error releasing advisory lock for process " + processId, e);
        } finally {
            try { con.close(); } catch (Exception ignored) {}
        }
    }

    static long toLockKey(String processId) {
        UUID uuid = UUID.fromString(processId);
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }
}
