package io.mateu.workflow.infra.out.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Lets the pod that just wrote to the outbox wake its own relay, instead of the relay finding out
 * on its next tick.
 *
 * <p>The poll interval is not a scheduling preference, it is latency added to every step: a
 * message waits on average half an interval before anyone looks. Measured on the benchmark
 * harness, roughly half the cost of a transition at a 20ms poll was that wait, and the shipped
 * default is 500ms. Waking on write removes it for the common case — the pod that wrote the row
 * is right there — while the poll stays as the fallback for rows written by other pods, which
 * this pod has no way of hearing about.
 *
 * <p><b>The signal must fire after the commit, not at the write.</b> Signalling inside the
 * transaction wakes the relay to look for a row it cannot see yet; it finds nothing, goes back to
 * waiting, and the wakeup is spent for nothing — leaving exactly the poll latency this exists to
 * remove, only now with an extra query.
 *
 * <p>One permit, not a count: the relay drains until empty, so it does not need to know how many
 * messages arrived, only that at least one did. Extra signals collapse into the one pending
 * permit rather than queueing up wakeups the relay would answer with empty queries.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@Slf4j
public class OutboxSignal {

    private final Semaphore permit = new Semaphore(0);

    /**
     * Wakes the relay once the current transaction commits, or immediately when there is none.
     * A rolled-back transaction signals nothing: there is no row to relay.
     */
    public void raise() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                release();
            }
        });
    }

    /** Waits for a signal, giving up after the poll interval so other pods' rows are still found. */
    boolean awaitWork(long timeoutMillis) throws InterruptedException {
        return permit.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void release() {
        if (permit.availablePermits() == 0) {
            permit.release();
        }
    }
}
