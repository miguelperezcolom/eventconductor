package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.usecases.lock.ExpireLockLeasesUseCase;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Periodically evicts held locks whose lease has expired — the crash backstop for a pod that took a
 * lock and died before releasing it. Only active in JPA mode (needs the JdbcTemplate for the
 * advisory lock); memory mode is a single JVM whose locks die with it. One pod at a time holds the
 * advisory lock, so the eviction query runs once per cluster per tick.
 *
 * <p>Deliberately infrequent: the lease is generous (15 min by default), so scanning every minute is
 * ample and keeps this off the hot path.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class LockLeaseReaper {

    // Advisory lock ids in use: 222333444 (CronStartScheduler), 444555666 (EmbeddedOutboxRelay),
    // 777888999 (TimeoutScheduler), 888999111 (here). Keep them distinct.
    private static final long LOCK_ID = 888999111L;

    final ExpireLockLeasesUseCase expireLockLeasesUseCase;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @Value("${workflow.lock.lease-scan-interval-ms:60000}")
    long scanIntervalMs;

    private volatile boolean running = true;
    private Thread thread;

    @PostConstruct
    public void start() {
        thread = new Thread(() -> {
            try {
                while (running) {
                    try {
                        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
                            try {
                                expireLockLeasesUseCase.handle();
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        if (running) {
                            log.error("Error reaping expired lock leases", e);
                        }
                    }
                    if (running) {
                        Thread.sleep(scanIntervalMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "workflow-lock-lease-reaper");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
