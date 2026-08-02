package io.mateu.workflow.infra.out.async;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes committed outbox messages to Kafka.
 *
 * <p><b>Every pod relays.</b> Batches are claimed with row locks that skip what other pods hold
 * (see {@link OutboxDrain}), so N orchestrators drain N disjoint slices and relay throughput
 * grows with the cluster. This used to be a leader-elected singleton: one pod drained the whole
 * outbox while the rest idled, which put a ceiling on the distributed topology that adding pods
 * could not lift — every state transition in kafka mode passes through here.
 *
 * <p>A pass that comes back with a full batch means more is waiting, so the loop keeps draining
 * until it does not — otherwise throughput would be capped at one batch per poll interval, and a
 * backlog would take as many intervals as it has batches. It stops early if a full batch settles
 * nothing, so messages that always fail cannot spin the loop.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@ConditionalOnProperty(name = "workflow.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    final OutboxDrain outboxDrain;
    final StreamBridge streamBridge;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox-poll-interval-ms:500}")
    long pollIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.batch-size:100}")
    int batchSize;

    @PostConstruct
    public void iterate() {
        var thread = new Thread(() -> {
            try {
                while (true) {
                    try {
                        drainUntilEmpty();
                    } catch (Throwable e) {
                        log.error("Error relaying outbox messages", e);
                    }
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "outbox-relay");
        thread.setDaemon(true);
        thread.start();
    }

    private void drainUntilEmpty() {
        // The gate is held in shared mode: relays never block each other, but the chaos tests
        // can freeze every one of them by taking it exclusively.
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            if (!dbLockDialect.tryRelayGate(con)) {
                return null;
            }
            try {
                OutboxDrain.Result result;
                do {
                    result = outboxDrain.drain(batchSize, payload ->
                            PartitionedEvents.send(streamBridge, "outbox", payload));
                } while (result.claimed() >= batchSize && result.settled() > 0);
            } finally {
                dbLockDialect.releaseRelayGate(con);
            }
            return null;
        });
    }
}
