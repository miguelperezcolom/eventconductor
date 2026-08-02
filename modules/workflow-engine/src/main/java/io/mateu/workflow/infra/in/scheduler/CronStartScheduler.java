package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically starts process instances for ACTIVE workflow definitions that declare a cron
 * expression. Only active in JPA mode (requires JdbcTemplate for the advisory lock).
 * The advisory lock (222333444L) ensures only one pod runs the scan at a time; across pods
 * the deterministic business key (definition id + occurrence time) makes duplicate creation
 * requests collapse into a single process through the creation idempotency guard.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class CronStartScheduler {

    // Advisory lock ids in use: 222333444 (here), 444555666 (EmbeddedOutboxRelay),
    // 777888999 (TimeoutScheduler). Keep them distinct.
    private static final long LOCK_ID = 222333444L;

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final UpstreamEventPublisher upstreamEventPublisher;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-scan-interval-ms:10000}")
    long scanIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-enabled:true}")
    boolean enabled;

    private final Map<String, LocalDateTime> lastFireTimes = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        if (!enabled) {
            return;
        }
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
                            try {
                                CronStarts.fireDue(workflowDefinitionRepository, upstreamEventPublisher,
                                        lastFireTimes, LocalDateTime.now());
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        log.error("Error checking cron process starts", e);
                    }
                    Thread.sleep(scanIntervalMs);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

}
