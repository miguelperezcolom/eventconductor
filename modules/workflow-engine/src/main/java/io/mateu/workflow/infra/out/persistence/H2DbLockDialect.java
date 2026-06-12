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
}
