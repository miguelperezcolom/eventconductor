package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessLockService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
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
 *
 * A watchdog thread runs every 60 s and force-releases any lock held for longer
 * than STALE_LOCK_THRESHOLD_SECONDS. Lock-protected operations are expected to
 * complete in well under a second, so a 60 s threshold is a safe safety net.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class JdbcProcessLockService implements ProcessLockService {

    private static final long STALE_LOCK_THRESHOLD_SECONDS = 60;
    private static final long WATCHDOG_INTERVAL_MS = 60_000;

    private record LockEntry(Connection connection, Instant acquiredAt) {}

    private final DataSource dataSource;
    private final DbLockDialect dialect;
    private final ConcurrentHashMap<Long, LockEntry> heldLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void startWatchdog() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                Instant cutoff = Instant.now().minusSeconds(STALE_LOCK_THRESHOLD_SECONDS);
                heldLocks.forEach((key, entry) -> {
                    if (entry.acquiredAt().isBefore(cutoff)) {
                        log.warn("Releasing stale lock {} held since {} (exceeded {}s threshold)",
                                key, entry.acquiredAt(), STALE_LOCK_THRESHOLD_SECONDS);
                        forceRelease(key, entry);
                    }
                });
            }
        }, "process-lock-watchdog");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean tryLock(String processId) {
        long key = toLockKey(processId);
        try {
            Connection con = dataSource.getConnection();
            if (dialect.tryLock(con, key)) {
                heldLocks.put(key, new LockEntry(con, Instant.now()));
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
        LockEntry entry = heldLocks.remove(key);
        if (entry == null) return;
        try {
            dialect.unlock(entry.connection(), key);
        } catch (Exception e) {
            throw new RuntimeException("Error releasing lock for process " + processId, e);
        } finally {
            try { entry.connection().close(); } catch (Exception ignored) {}
        }
    }

    private void forceRelease(long key, LockEntry entry) {
        if (heldLocks.remove(key, entry)) {
            try {
                dialect.unlock(entry.connection(), key);
            } catch (Exception e) {
                log.error("Error force-releasing stale lock {}", key, e);
            } finally {
                try { entry.connection().close(); } catch (Exception ignored) {}
            }
        }
    }

    static long toLockKey(String processId) {
        UUID uuid = UUID.fromString(processId);
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }
}
