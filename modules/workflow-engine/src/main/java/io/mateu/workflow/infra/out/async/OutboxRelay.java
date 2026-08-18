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
 * <p>The loop does not simply sleep between passes: it waits on {@link OutboxSignal}, which the
 * pod raises after committing a write of its own. The poll interval becomes a fallback for rows
 * written by other pods rather than the latency every step pays — measured on the benchmark
 * harness, that wait was about half the cost of a transition.
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
    final OutboxSignal outboxSignal;
    final io.mateu.workflow.application.out.WorkflowMetrics workflowMetrics;
    final StreamBridge streamBridge;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox-poll-interval-ms:500}")
    long pollIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.batch-size:100}")
    int batchSize;

    // When sharded, a SEND_MESSAGE step's MessageReceived (which rides this process's outbox) is relayed
    // to the shared cross-shard `messages` topic instead of this shard's `outbox`, so it can reach a
    // waiter on any shard. Off (default) → messages stay on `outbox`, single-cluster behaviour unchanged.
    @org.springframework.beans.factory.annotation.Value("${workflow.sharding.enabled:false}")
    boolean sharedMessages;

    // In remote projection mode the read model is maintained by a standalone projector against a read
    // database this shard does not own, so ProcessStatusChanged is relayed to the shared projection
    // topic instead of this shard's `outbox`. Embedded (the default) → it stays on `outbox` and the
    // in-process projector handles it, exactly as before.
    @org.springframework.beans.factory.annotation.Value("${workflow.projection.mode:embedded}")
    String projectionMode;

    @PostConstruct
    public void iterate() {
        var thread = new Thread(() -> {
            try {
                while (true) {
                    var cycleStartedAt = System.nanoTime();
                    try {
                        drainUntilEmpty();
                    } catch (Throwable e) {
                        log.error("Error relaying outbox messages", e);
                    }
                    var drainedAt = System.nanoTime();
                    // Woken by this pod's own writes, and falling back to the poll for rows
                    // written by other pods, which there is no way to hear about directly.
                    outboxSignal.awaitWork(pollIntervalMs);
                    // Draining against waiting is this thread's duty cycle. It is one thread per
                    // pod and every transition in kafka mode passes through it, so a duty cycle
                    // that sits near 1 is the ceiling itself and not a symptom of one.
                    workflowMetrics.outboxRelayCycle(
                            java.time.Duration.ofNanos(drainedAt - cycleStartedAt),
                            java.time.Duration.ofNanos(System.nanoTime() - drainedAt));
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
                    result = outboxDrain.drain(batchSize, event ->
                            PartitionedEvents.send(streamBridge, bindingFor(event), event));
                } while (result.claimed() >= batchSize && result.settled() > 0);
            } finally {
                dbLockDialect.releaseRelayGate(con);
            }
            return null;
        });
    }

    /** See {@link RelayDestination} — where a relayed event goes, and why anything leaves `outbox`. */
    private String bindingFor(io.mateu.workflow.ddd.DomainEvent event) {
        return RelayDestination.bindingFor(event, sharedMessages, "remote".equals(projectionMode));
    }
}
