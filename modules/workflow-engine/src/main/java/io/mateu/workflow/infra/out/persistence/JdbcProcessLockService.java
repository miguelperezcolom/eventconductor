package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-process exclusion as a row lock on the process itself: the action runs in a transaction
 * that opens by taking {@code SELECT … FOR UPDATE} on its row, and the commit releases it.
 *
 * <p>This replaced an advisory lock, and the reason was not elegance. That implementation took a
 * connection out of the pool and <b>held it for the whole critical section</b> — advisory locks
 * are session-scoped, so lock and unlock had to run on the same session — while the work inside
 * needed a second connection of its own. Two connections per in-flight process meant the pool
 * size, not the database, capped concurrency, and past that point the failure mode was not
 * slowness but a wedge: lock holders waiting for a connection to do the work they hold the lock
 * for. It also needed a watchdog to force-release locks held too long, which could take
 * exclusivity away from an operation that was still running.
 *
 * <p>The row lock costs one connection — the one the work already uses — is released by the
 * commit rather than by remembering to, queues fairly in the database instead of sleeping and
 * retrying, and is reentrant within a transaction. Waiting is bounded by a statement timeout,
 * which is portable across the supported databases in a way that per-vendor lock-timeout
 * settings are not.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class JdbcProcessLockService implements ProcessLockService {

    final JdbcTemplate jdbcTemplate;
    final TransactionTemplate transactionTemplate;

    @Value("${workflow.process-lock-timeout-seconds:10}")
    int lockTimeoutSeconds;

    @Override
    public boolean runExclusively(String processId, Runnable action) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                lockRow(processId);
                action.run();
                return true;
            }));
        } catch (Exception e) {
            // A statement timeout waiting for the row, or a deadlock the database broke. Either
            // way another node has this process; the caller decides whether that deserves a log
            // line or whether the event that triggered it will simply come round again.
            log.warn("Could not obtain exclusive access to process {} ({})", processId, e.getMessage());
            return false;
        }
    }

    private void lockRow(String processId) {
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            try (var ps = con.prepareStatement("SELECT id FROM process_entity WHERE id = ? FOR UPDATE")) {
                ps.setQueryTimeout(lockTimeoutSeconds);
                ps.setString(1, processId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // Nothing can be concurrently modifying a process that does not exist,
                        // so let the action run and fail on its own lookup with an error that
                        // says so, rather than reporting this as a lock problem.
                        log.debug("No process row {} to lock", processId);
                    }
                }
            }
            return null;
        });
    }
}
