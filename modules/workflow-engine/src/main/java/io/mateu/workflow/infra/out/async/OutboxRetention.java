package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes outbox rows that have already been sent, so the table has a size rather than a history.
 *
 * <p>Nothing used to remove them. Every state transition of every process leaves its events in this
 * table forever — order of twenty-five rows per process instance — so a twenty-million-process run
 * ends with hundreds of millions of rows that are only ever inserted and updated. The claim's index
 * on {@code (status, timestamp)} stays selective, so this is not felt as a slow query; it is felt as
 * a table that autovacuum has to keep rewriting, a heap every index scan pays for, and a disk that
 * fills on day three of a four-day run. That failure mode is invisible at sixty thousand rows,
 * which is where every measurement so far has been taken.
 *
 * <h2>What it will not do</h2>
 *
 * <p>Only {@code Sent} rows, and only ones older than the configured window. A Pending row is
 * undelivered work and an Error row is a parked message waiting for a human, so both outlive any
 * retention setting — the filter lives in {@link DbLockDialect#purgeSentOutboxSql()} and is
 * repeated per dialect rather than composed, because it is the whole safety argument.
 *
 * <p><b>Off unless asked for.</b> {@code workflow.outbox.retention} has no default: deleting rows
 * is the one thing here that cannot be undone, and an engine that quietly discarded an embedder's
 * outbox because it was upgraded would be a worse defect than the one this fixes. Set it to a
 * window comfortably longer than any forensic question anyone asks of that table — {@code 7d} is a
 * reasonable start, {@code 24h} is enough for a benchmark run.
 *
 * <p>The delete is bounded and repeated rather than issued once. An unbounded delete over hundreds
 * of millions of rows is one transaction and one lock held long enough to matter to everything else
 * on that database, which would make the cure worse than the disease on precisely the deployments
 * that need it.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@ConditionalOnProperty(name = "workflow.outbox.retention")
@RequiredArgsConstructor
@Slf4j
public class OutboxRetention {

    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;
    final TransactionTemplate transactionTemplate;

    /** How long a sent row is kept. Accepts {@code 24h}, {@code 7d}, or ISO-8601 {@code P7D}. */
    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.retention}")
    Duration retention;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.purge-batch-size:1000}")
    int purgeBatchSize;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.purge-interval:PT1M}")
    Duration purgeInterval;

    /**
     * How many batches one pass may delete. A pod that has been down while the table grew should
     * catch up over several passes rather than in one transaction-heavy sweep that competes with
     * the relays for the same database.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.purge-max-batches-per-pass:20}")
    int maxBatchesPerPass;

    private ScheduledExecutorService purger;

    @PostConstruct
    void start() {
        log.info("Outbox retention on: sent messages older than {} are purged every {}, "
                + "{} rows at a time", retention, purgeInterval, purgeBatchSize);
        purger = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "outbox-retention");
            thread.setDaemon(true);
            return thread;
        });
        purger.scheduleWithFixedDelay(this::purgeQuietly,
                purgeInterval.toMillis(), purgeInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (purger != null) {
            purger.shutdownNow();
        }
    }

    /**
     * Housekeeping never takes the engine down with it: a database that refuses this delete is a
     * table that keeps growing, which is a problem for tomorrow, whereas an exception escaping a
     * scheduled task cancels the schedule silently and permanently.
     */
    private void purgeQuietly() {
        try {
            var purged = purge();
            if (purged > 0) {
                log.info("Purged {} sent outbox messages older than {}", purged, retention);
            }
        } catch (Exception e) {
            log.error("Outbox retention pass failed, will try again next interval", e);
        }
    }

    /** Returns how many rows this pass removed. Package-private so a test can run one pass. */
    int purge() {
        var cutoff = Timestamp.valueOf(LocalDateTime.now().minus(retention));
        var total = 0;
        for (var pass = 0; pass < maxBatchesPerPass; pass++) {
            var deleted = deleteOneBatch(cutoff);
            total += deleted;
            if (deleted < purgeBatchSize) {
                break;
            }
        }
        return total;
    }

    /**
     * Selects the ids, then deletes exactly those by primary key.
     *
     * <p>Two statements rather than one, deliberately — see
     * {@link DbLockDialect#selectSentOutboxToPurgeSql()}. A single delete carrying the limit inside
     * an {@code IN} subquery is not reliably bounded, and the way it fails is to delete far fewer
     * rows than asked, so a purge that can never keep up looks exactly like one that is working.
     */
    private int deleteOneBatch(Timestamp cutoff) {
        var deleted = transactionTemplate.execute(status -> {
            var ids = jdbcTemplate.queryForList(
                    dbLockDialect.selectSentOutboxToPurgeSql(), String.class, cutoff, purgeBatchSize);
            if (ids.isEmpty()) {
                return 0;
            }
            var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            return jdbcTemplate.update(
                    "DELETE FROM outbox_message_entity WHERE id IN (" + placeholders + ")",
                    ids.toArray());
        });
        return deleted == null ? 0 : deleted;
    }
}
