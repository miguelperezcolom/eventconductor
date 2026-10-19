package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.services.ProcessRetentionService;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the process retention purge ({@link ProcessRetentionService}) every
 * {@code workflow.processes.purge.interval} (10 minutes by default).
 *
 * <p>On a database, one pod at a time: the pass runs under an advisory lock, like the cron and timeout
 * scanners, so a cluster does not have every pod walking the same trees and refusing each other's
 * deletes. In memory mode there is one JVM and no lock to take.
 *
 * <p>Always scheduled unless {@code workflow.processes.purge.enabled=false}: whether anything is
 * deleted is up to the retentions — a definition's own, then the engine defaults, which are off
 * unless configured — and a pass with nothing configured reads the definitions and stops. A failed
 * pass is logged and retried on the next interval, never allowed to cancel the schedule.
 */
@Component
@Slf4j
public class ProcessRetentionJob implements SchedulerPass {

    // Advisory lock ids in use: 222333444 (CronStartScheduler), 333444555 (AnalyticsRollupProjector),
    // 444555666 (EmbeddedOutboxRelay), 777888999 (TimeoutScheduler), 888999111 (LockLeaseReaper),
    // 555666777 (here). Keep them distinct.
    static final long LOCK_ID = 555666777L;

    final ProcessRetentionService service;
    final ProcessRetentionProperties properties;
    final ObjectProvider<JdbcTemplate> jdbcTemplate;
    final ObjectProvider<DbLockDialect> dbLockDialect;

    private ScheduledExecutorService purger;

    @org.springframework.beans.factory.annotation.Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    public ProcessRetentionJob(ProcessRetentionService service, ProcessRetentionProperties properties,
                               ObjectProvider<JdbcTemplate> jdbcTemplate, ObjectProvider<DbLockDialect> dbLockDialect) {
        this.service = service;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.dbLockDialect = dbLockDialect;
    }

    @PostConstruct
    void start() {
        var interval = properties.getPurge().getInterval();
        if (!properties.getPurge().isEnabled() || interval == null || interval.isZero() || interval.isNegative()) {
            log.info("Process retention purge is off (workflow.processes.purge)");
            return;
        }
        if (!background) {
            return;
        }
        if (service.policy().hasDefaults()) {
            log.info("Process retention on: finished processes past their retention ({}) are purged every {}",
                    service.policy().describe(null), interval);
        }
        purger = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "process-retention");
            thread.setDaemon(true);
            return thread;
        });
        purger.scheduleWithFixedDelay(this::purgeQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (purger != null) {
            purger.shutdownNow();
        }
    }

    void purgeQuietly() {
        try {
            var jdbc = jdbcTemplate.getIfAvailable();
            var dialect = dbLockDialect.getIfAvailable();
            if (jdbc == null || dialect == null) {
                purgeOnce();
                return;
            }
            jdbc.execute((ConnectionCallback<Void>) con -> {
                if (!dialect.tryLock(con, LOCK_ID)) {
                    return null; // another pod is purging
                }
                try {
                    purgeOnce();
                } finally {
                    dialect.unlock(con, LOCK_ID);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Process retention pass failed, will try again next interval", e);
        }
    }

    /**
     * One purge pass on the calling thread, as the background loop runs it (under the advisory lock
     * when there is a database). Does nothing when the purge is off.
     */
    @Override
    public int runOnce() {
        if (!properties.getPurge().isEnabled()) {
            return 0;
        }
        var purged = new java.util.concurrent.atomic.AtomicInteger();
        var jdbc = jdbcTemplate.getIfAvailable();
        var dialect = dbLockDialect.getIfAvailable();
        if (jdbc == null || dialect == null) {
            return (int) purgeOnce().total();
        }
        jdbc.execute((ConnectionCallback<Void>) con -> {
            if (dialect.tryLock(con, LOCK_ID)) {
                try {
                    purged.set((int) purgeOnce().total());
                } finally {
                    dialect.unlock(con, LOCK_ID);
                }
            }
            return null;
        });
        return purged.get();
    }

    /** One pass, logged when it deleted or kept anything. */
    ProcessRetentionService.PurgeReport purgeOnce() {
        var report = service.purgeDue(EngineClock.now());
        if (report.total() > 0 || report.treesKept() > 0) {
            log.info("Purged {} finished processes past their retention {}{}", report.total(), report.purged(),
                    report.treesKept() > 0
                            ? "; kept " + report.treesKept() + " due process trees still in use (unfinished child,"
                            + " step at work or awaiting retry, lock, or unsent outbox message)"
                            : "");
        }
        return report;
    }
}
