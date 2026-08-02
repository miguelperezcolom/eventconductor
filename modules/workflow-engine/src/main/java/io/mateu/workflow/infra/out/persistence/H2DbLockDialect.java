package io.mateu.workflow.infra.out.persistence;

import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class H2DbLockDialect implements DbLockDialect {

    private static final ConcurrentHashMap<Long, AtomicBoolean> LOCKS = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(Connection con, long lockId) {
        return LOCKS.computeIfAbsent(lockId, k -> new AtomicBoolean(false))
                .compareAndSet(false, true);
    }

    @Override
    public void unlock(Connection con, long lockId) {
        AtomicBoolean lock = LOCKS.get(lockId);
        if (lock != null) lock.set(false);
    }

    /**
     * H2 accepts the same statement but locks every row the query matches, not only the ones it
     * returns, so a second relay claims nothing until the first commits — correct, and no worse
     * than the leader lock this replaced, but not concurrent. H2 is the test database; the
     * concurrency this pattern exists for is exercised against real PostgreSQL in the
     * distributed suite (DIST-09).
     */
    @Override
    public String claimPendingOutboxSql() {
        return "SELECT id FROM outbox_message_entity WHERE status = 'Pending' "
                + "ORDER BY timestamp LIMIT ? FOR UPDATE SKIP LOCKED";
    }
}
