package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Forgets inbound event receipts once {@code workflow.events.receipts.retention} (7 days by default)
 * has passed: past it, a redelivery of that event is a new event again. Every pod may run it — the
 * delete is idempotent and bounded by the received-at index — and a failed pass is logged and retried.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboundEventReceiptRetention implements SchedulerPass {

    final EventReceiptRepository receipts;
    final EventDestinationsProperties properties;

    private ScheduledExecutorService purger;

    @org.springframework.beans.factory.annotation.Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    @PostConstruct
    void start() {
        var interval = properties.getReceipts().getPurgeInterval().toMillis();
        if (interval <= 0 || !background) {
            return;
        }
        purger = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "inbound-event-receipt-retention");
            thread.setDaemon(true);
            return thread;
        });
        purger.scheduleWithFixedDelay(this::purgeQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (purger != null) {
            purger.shutdownNow();
        }
    }

    /** One pass: forgets the inbound event receipts older than their retention. */
    @Override
    public int runOnce() {
        var purged = receipts.purgeReceivedBefore(EngineClock.instant().minus(properties.getReceipts().getRetention()));
        if (purged > 0) {
            log.info("Forgot {} inbound event receipts past their retention", purged);
        }
        return purged;
    }

    void purgeQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Could not purge inbound event receipts; will retry", e);
        }
    }
}
