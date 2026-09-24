package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.InvocationRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Forgets synchronous invocations once their retention ({@code workflow.sync.retention}, 24 h by
 * default) has passed. Only the request record goes: the process and its reply are untouched, so
 * the answer stays readable on the process; what expires is the ability to find it again by
 * idempotency key.
 *
 * <p>Every pod may run it — the delete is idempotent and bounded by the expiry index — and a failed
 * pass is logged and retried, never allowed to cancel the schedule.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvocationRetention {

    final InvocationRepository invocationRepository;

    @org.springframework.beans.factory.annotation.Value("${workflow.sync.purge-interval:PT10M}")
    Duration purgeInterval;

    private ScheduledExecutorService purger;

    @PostConstruct
    void start() {
        purger = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "sync-invocation-retention");
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

    void purgeQuietly() {
        try {
            var purged = invocationRepository.purgeExpired(LocalDateTime.now());
            if (purged > 0) {
                log.info("Forgot {} expired synchronous invocations", purged);
            }
        } catch (Exception e) {
            log.warn("Synchronous invocation retention pass failed, will try again: {}", e.getMessage());
        }
    }
}
