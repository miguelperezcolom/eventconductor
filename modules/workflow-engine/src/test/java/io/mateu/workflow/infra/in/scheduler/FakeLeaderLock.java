package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The advisory lock the JPA schedulers take to be the one pod that scans, without a database: a
 * {@link JdbcTemplate} that hands every callback a connection, and a dialect whose answer to
 * "am I the leader?" the test decides. It records which lock ids were taken and released.
 */
final class FakeLeaderLock implements DbLockDialect {

    volatile boolean leader = true;
    final List<Long> locked = new CopyOnWriteArrayList<>();
    final List<Long> unlocked = new CopyOnWriteArrayList<>();
    final AtomicInteger attempts = new AtomicInteger();

    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @SuppressWarnings("unchecked")
    FakeLeaderLock() {
        var connection = mock(Connection.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<Object>) invocation.getArgument(0)).doInConnection(connection));
    }

    @Override
    public boolean tryLock(Connection con, long lockId) {
        attempts.incrementAndGet();
        if (!leader) {
            return false;
        }
        locked.add(lockId);
        return true;
    }

    @Override
    public void unlock(Connection con, long lockId) {
        unlocked.add(lockId);
    }

    @Override
    public String claimPendingOutboxSql() {
        throw new UnsupportedOperationException();
    }
}
