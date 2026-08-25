package io.mateu.workflowdist;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.infra.out.async.OutboxDrain;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-19 — What sending a batch concurrently is not allowed to cost.
 *
 * <p>The relay now starts a virtual thread per partition key, so hundreds of sends are in flight
 * where four used to be. That is the point, and these are the two things it must not have bought.
 *
 * <p><b>Order within a process.</b> Events are keyed by process, so order per process is order per
 * partition — and the engine has no way to detect a reordering it caused itself. The guarantee here
 * is by construction rather than by configuration: within a key the sends stay strictly sequential,
 * one ack at a time, and only different keys go concurrently. That is deliberate. Firing a key's
 * messages without waiting would make ordering a property of {@code enable.idempotence} and
 * {@code max.in.flight.requests.per.connection} — both pinned by {@code SynchronousProducerDefaults},
 * both correct, and both a setting somebody could change while this test stayed green.
 *
 * <p><b>A refusal settles nothing it did not deliver.</b> The outbox's whole claim is that a row is
 * Sent only if the broker took it. Seventy-one of 642 912 messages were once marked Sent and never
 * reached the topic, and each one is a process that stops forever. Concurrency multiplies the ways
 * to get this wrong, so it is asserted at the row level: after a pass that refuses part of the
 * batch, exactly the delivered rows are Sent and exactly the refused ones are still Pending.
 *
 * <p>Verified to discriminate — see each test.
 */
class Dist19OutboxBatchInvariantsTest extends AbstractDistTest {

    private ConfigurableApplicationContext orchestrator;

    @AfterEach
    void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    /**
     * Many events for one process, drained in one pass with a thread per key.
     *
     * <p>Verified to discriminate: replacing {@code deliverInOrder}'s sequential loop with a
     * concurrent one over the same group — the shortcut this design deliberately does not take —
     * fails here with the events interleaved, e.g. {@code [0, 2, 1, 4, 3, ...]} instead of in order.
     */
    @Test
    void everyEventOfOneProcessIsDeliveredInOrder() {
        orchestrator = start(0);
        var outbox = orchestrator.getBean(OutboxMessageEntityRepository.class);
        var drain = orchestrator.getBean(OutboxDrain.class);
        outbox.deleteAll();

        // One process, 200 transitions. They share a key, so nothing about them may go concurrently.
        var rows = new ArrayList<OutboxMessageEntity>();
        for (var i = 0; i < 200; i++) {
            rows.add(row("only-process", i));
        }
        outbox.saveAll(rows);

        var delivered = new CopyOnWriteArrayList<Integer>();
        var result = drain.drain(500, event -> {
            // A little work per send, so a concurrent implementation would have room to interleave
            // rather than finishing each one before the next began by accident.
            sleepAMoment();
            delivered.add(completionOf(event));
        });

        assertThat(result.settled()).isEqualTo(200);
        assertThat(delivered).hasSize(200);
        assertThat(delivered)
                .as("a process's events must reach the topic in the order they happened")
                .isSorted();
    }

    /**
     * A broker that refuses some of the batch.
     *
     * <p>Verified to discriminate: marking the whole claimed batch Sent regardless — the shape the
     * outbox had before the send's return value was checked — leaves zero rows Pending here, and
     * the assertion names them.
     */
    @Test
    void aRefusalLeavesExactlyTheUndeliveredRowsPending() {
        orchestrator = start(0);
        var outbox = orchestrator.getBean(OutboxMessageEntityRepository.class);
        var drain = orchestrator.getBean(OutboxDrain.class);
        outbox.deleteAll();

        // 60 processes, one event each, so every one is its own key and its own fate.
        var rows = new ArrayList<OutboxMessageEntity>();
        for (var i = 0; i < 60; i++) {
            rows.add(row("p-" + i, 0));
        }
        outbox.saveAll(rows);

        // Every third key is refused, and the refusals are spread through the batch rather than
        // clustered at one end — a bug that settled "everything after the first failure" and a bug
        // that settled "everything before it" would both survive a batch that failed at an edge.
        var refused = ConcurrentHashMap.<String>newKeySet();
        var accepted = ConcurrentHashMap.<String>newKeySet();
        drain.drain(500, event -> {
            var key = event.partitionKey();
            if (Integer.parseInt(key.substring(2)) % 3 == 0) {
                refused.add(key);
                throw new IllegalStateException("the broker refused " + key);
            }
            accepted.add(key);
        });

        assertThat(refused).hasSize(20);
        assertThat(accepted).hasSize(40);

        var byStatus = new ConcurrentHashMap<String, AtomicInteger>();
        outbox.findAll().forEach(message ->
                byStatus.computeIfAbsent(message.getStatus(), s -> new AtomicInteger()).incrementAndGet());

        assertThat(byStatus.get("Sent").get())
                .as("only what the broker took is Sent")
                .isEqualTo(40);
        assertThat(byStatus.get("Pending").get())
                .as("every refused row is still Pending, for the next pass to retry")
                .isEqualTo(20);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    private ConfigurableApplicationContext start(int relayConcurrency) {
        return DistInfra.startOrchestrator(Map.of(
                // The background loop off: this pass is the only one running, so the rows the
                // assertions read are the ones this pass left behind.
                "workflow.outbox.relay-enabled", false,
                "workflow.outbox.relay-concurrency", relayConcurrency,
                "workflow.outbox.batch-size", 500));
    }

    private static OutboxMessageEntity row(String processId, int completion) {
        return new OutboxMessageEntity(new ProcessStatusChanged(
                processId, "bk-" + processId, "a process", "wd-1", 1, "RUNNING", completion,
                LocalDateTime.now(), LocalDateTime.now(), null, LocalDateTime.now(), null));
    }

    /** The sequence number this test smuggles through the event's completion percentage. */
    private static int completionOf(DomainEvent event) {
        return ((ProcessStatusChanged) event).completionPercentage();
    }

    private static void sleepAMoment() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
