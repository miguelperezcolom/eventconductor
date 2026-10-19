package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;
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
public class CronStartScheduler implements SchedulerPass {

    // Advisory lock ids in use: 222333444 (here), 444555666 (EmbeddedOutboxRelay),
    // 777888999 (TimeoutScheduler). Keep them distinct.
    private static final long LOCK_ID = 222333444L;

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final io.mateu.workflow.application.out.CronScheduleRepository cronScheduleRepository;
    final io.mateu.workflow.application.out.SchedulePauseRepository schedulePauseRepository;
    final IngressRouter ingressRouter;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-scan-interval-ms:10000}")
    long scanIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.cron-enabled:true}")
    boolean enabled;

    @org.springframework.beans.factory.annotation.Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    private final Map<String, ZonedDateTime> lastFireTimes = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private Thread cronSchedulerThread;

    @PostConstruct
    public void start() {
        if (!enabled || !background) {
            return;
        }
        cronSchedulerThread = new Thread(() -> {
            try {
                while (running) {
                    try {
                        runOnce();
                    } catch (Throwable e) {
                        if (running) {
                            log.error("Error checking cron process starts", e);
                        }
                    }
                    if (running) {
                        Thread.sleep(scanIntervalMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "workflow-cron-scheduler");
        cronSchedulerThread.setDaemon(true);
        cronSchedulerThread.start();
    }

    /**
     * One cron scan under the cluster-wide advisory lock: starts a process for every occurrence
     * elapsed since the last scan. Zero when another pod holds the lock or cron starts are off.
     */
    @Override
    public int runOnce() {
        if (!enabled) {
            return 0;
        }
        var started = new java.util.concurrent.atomic.AtomicInteger();
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
            try {
                CronStarts.fireDue(workflowDefinitionRepository, cronScheduleRepository, schedulePauseRepository,
                        request -> {
                            started.incrementAndGet();
                            ingressRouter.route(request);
                        }, lastFireTimes, EngineClock.instant());
            } finally {
                dbLockDialect.unlock(con, LOCK_ID);
            }
            return null;
        });
        return started.get();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (cronSchedulerThread != null) {
            cronSchedulerThread.interrupt();
        }
    }

}
