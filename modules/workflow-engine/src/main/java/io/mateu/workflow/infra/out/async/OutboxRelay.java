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
    final StreamBridge streamBridge;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox-poll-interval-ms:500}")
    long pollIntervalMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.batch-size:100}")
    int batchSize;

    // When on, a SEND_MESSAGE step's MessageReceived (which rides this process's outbox) is relayed to
    // the shared cross-shard `messages` topic instead of this shard's `outbox`, so it can reach a waiter
    // on any shard. Off (default) → messages stay on `outbox`, single-cluster behaviour unchanged.
    @org.springframework.beans.factory.annotation.Value("${workflow.messages.shared-topic:false}")
    boolean sharedMessages;

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
                    // Woken by this pod's own writes, and falling back to the poll for rows
                    // written by other pods, which there is no way to hear about directly.
                    outboxSignal.awaitWork(pollIntervalMs);
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

    /**
     * The topic a relayed outbox event goes to: the shared {@code messages} topic for a
     * {@link io.mateu.workflow.dtos.events.integration.MessageReceived} when cross-shard messaging is
     * on, so it reaches a waiter on any shard; otherwise this shard's own {@code outbox}, unchanged.
     */
    private String bindingFor(io.mateu.workflow.ddd.DomainEvent event) {
        return (sharedMessages && event instanceof io.mateu.workflow.dtos.events.integration.MessageReceived)
                ? "messages" : "outbox";
    }
}
