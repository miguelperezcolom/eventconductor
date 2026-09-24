package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The crash backstop of the synchronous fast path: outbox rows whose inline claim has lapsed — the
 * pod that was driving them died, or could not reach the database to renew — go back to
 * {@code Pending}, where the relay picks them up and the process continues on the normal path.
 *
 * <p>Every pod runs it; the update is idempotent and touches only expired claims, which a live pod
 * never has (it renews every third of the lease).
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class InlineClaimSweeper {

    final JdbcTemplate jdbcTemplate;
    final OutboxSignal outboxSignal;

    @org.springframework.beans.factory.annotation.Value("${workflow.sync.inline.sweep-interval-ms:5000}")
    long sweepIntervalMs = 5_000;

    private ScheduledExecutorService sweeper;

    @PostConstruct
    void start() {
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "sync-inline-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweepQuietly, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /** Returns how many lapsed claims went back to the relay. Package-private for tests. */
    int sweep() {
        var released = jdbcTemplate.update(
                "UPDATE outbox_message_entity SET status = ?, claimed_by = NULL, claim_until = NULL"
                        + " WHERE status = ? AND claim_until < ?",
                OutboxMessageStatus.Pending.name(), OutboxMessageStatus.InlineClaimed.name(),
                Timestamp.valueOf(LocalDateTime.now()));
        if (released > 0) {
            log.warn("Handed {} outbox messages with a lapsed inline claim back to the relay", released);
            outboxSignal.raise();
        }
        return released;
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Exception e) {
            log.warn("Inline claim sweep failed, will try again: {}", e.toString());
        }
    }
}
