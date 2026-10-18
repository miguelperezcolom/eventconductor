package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The batch operations runner: every {@code workflow.batch-operations.poll-interval} it works one page of
 * the oldest active batch ({@link BatchOperationService#runNextPage}).
 *
 * <p>On a database, one pod at a time: the page runs under an advisory lock, like the retention purge and
 * the cron and timeout scanners, so two pods never walk the same batch and act on a process twice. The
 * cursor and the counters are saved after every page, so a pod that dies mid-batch loses at most the
 * page in hand — whichever pod takes the lock next carries on from the cursor, and a command repeated on
 * a process already moved is refused by its status rule and counted as skipped. In memory mode there is
 * one JVM and no lock to take.
 *
 * <p>Off with {@code workflow.batch-operations.enabled=false}: batches are still created and cancelled,
 * and wait as CREATED. A failed page is logged and tried again on the next tick.
 */
@Component
@Slf4j
public class BatchOperationJob {

    // Advisory lock ids in use: 222333444 (CronStartScheduler), 333444555 (AnalyticsRollupProjector),
    // 444555666 (EmbeddedOutboxRelay), 555666777 (ProcessRetentionJob), 777888999 (TimeoutScheduler),
    // 888999111 (LockLeaseReaper), 666777888 (here). Keep them distinct.
    static final long LOCK_ID = 666777888L;

    final BatchOperationService service;
    final BatchOperationProperties properties;
    final ObjectProvider<JdbcTemplate> jdbcTemplate;
    final ObjectProvider<DbLockDialect> dbLockDialect;

    private ScheduledExecutorService runner;

    public BatchOperationJob(BatchOperationService service, BatchOperationProperties properties,
                             ObjectProvider<JdbcTemplate> jdbcTemplate, ObjectProvider<DbLockDialect> dbLockDialect) {
        this.service = service;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.dbLockDialect = dbLockDialect;
    }

    @PostConstruct
    void start() {
        var interval = properties.getPollInterval();
        if (!properties.isEnabled() || interval == null || interval.isZero() || interval.isNegative()) {
            log.info("Batch operations runner is off on this node (workflow.batch-operations.enabled)");
            return;
        }
        runner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "batch-operations");
            thread.setDaemon(true);
            return thread;
        });
        runner.scheduleWithFixedDelay(this::runQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (runner != null) {
            runner.shutdownNow();
        }
    }

    void runQuietly() {
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("Batch operations page failed, will try again next interval", e);
        }
    }

    /** One page, under the advisory lock when there is a database. @return whether this node worked one */
    boolean runOnce() {
        var jdbc = jdbcTemplate.getIfAvailable();
        var dialect = dbLockDialect.getIfAvailable();
        if (jdbc == null || dialect == null) {
            return service.runNextPage();
        }
        var worked = jdbc.execute((ConnectionCallback<Boolean>) con -> {
            if (!dialect.tryLock(con, LOCK_ID)) {
                return false; // another pod is running batches
            }
            try {
                return service.runNextPage();
            } finally {
                dialect.unlock(con, LOCK_ID);
            }
        });
        return Boolean.TRUE.equals(worked);
    }
}
