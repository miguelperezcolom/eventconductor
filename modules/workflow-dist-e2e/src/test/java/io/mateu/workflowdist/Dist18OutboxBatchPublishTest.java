package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.infra.out.async.OutboxDrain;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-18 — The relay puts a batch into the broker's requests, not one message per round trip.
 *
 * <p>On a saturated cluster the engine drained 148 transitions/s with the broker idle at 0.23 of two
 * cores and 28 068 processes queued. Nothing was busy. The outbox held six rows, so the relay was
 * not behind on reading its backlog — it was slow at publishing, and the queue formed in front of
 * it.
 *
 * <p>The ack barrier was already there: groups keyed by process, sequential within a key, run
 * concurrently, joined at the end. What was not there was <b>enough keys in flight to fill a broker
 * request</b>. The pool was sized to {@code relay-concurrency} platform threads, so the producer
 * never held more than that many records at once and amortised its round trip across four instead
 * of across the batch. Virtual threads make the same shape affordable at the batch's own size.
 *
 * <p>This measures the two side by side on a real broker: the same rows, drained with the pool
 * capped where it used to be and with one thread per key. Both numbers are printed, because the
 * ratio is the claim and a bound on its own would not tell you why it passed.
 *
 * <p><b>What it cannot do</b> is reproduce the cluster. A Testcontainers broker sharing a machine
 * with the engine and this test is not four nodes under saturation, so the absolute figures here
 * are not the 148/s and must not be read as it. What transfers is the shape and the direction.
 *
 * <p>Verified to discriminate: with the pool back to fixed platform threads at the old default, the
 * two measurements converge and the assertion on the ratio fails — reported in the pull request.
 */
class Dist18OutboxBatchPublishTest extends AbstractDistTest {

    static final int MESSAGES = 2_000;

    private ConfigurableApplicationContext orchestrator;

    @AfterEach
    void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void oneThreadPerKeyDrainsTheBatchFasterThanAHandfulOfThreadsDoes() {
        var capped = drainMillisWith(4);
        var perKey = drainMillisWith(0);

        System.out.printf("OUTBOX| %d messages, one key each%n", MESSAGES);
        System.out.printf("OUTBOX| relay-concurrency=4 (as it was):   %5d ms  -> %6.0f msg/s%n",
                capped, MESSAGES * 1000.0 / capped);
        System.out.printf("OUTBOX| relay-concurrency=0 (per key):     %5d ms  -> %6.0f msg/s%n",
                perKey, MESSAGES * 1000.0 / perKey);
        System.out.printf("OUTBOX| the relay transaction ran %d ms in the batched pass%n", perKey);

        assertThat(perKey)
                .as("one thread per key must beat a handful by a margin, not by a nose — "
                        + "perKey=%dms capped=%dms", perKey, capped)
                .isLessThan(capped / 2);
    }

    /** Drains {@value #MESSAGES} pending rows in one pass and returns the wall clock it took. */
    private long drainMillisWith(int relayConcurrency) {
        orchestrator = DistInfra.startOrchestrator(Map.of(
                // The relay's own loop off, so the pass under measurement is the only one running
                // and the number is not a race between it and a background drain.
                "workflow.outbox.relay-enabled", false,
                "workflow.outbox.relay-concurrency", relayConcurrency,
                "workflow.outbox.batch-size", MESSAGES));
        var outbox = orchestrator.getBean(OutboxMessageEntityRepository.class);
        var drain = orchestrator.getBean(OutboxDrain.class);
        outbox.deleteAll();
        seed(outbox);

        var streamBridge = orchestrator.getBean(StreamBridge.class);
        var startedAt = System.nanoTime();
        var result = drain.drain(MESSAGES, event -> {
            // The same send the relay makes: keyed, and synchronous because
            // SynchronousProducerDefaults pins sync=true — so this waits on a real ack from a real
            // broker, which is the whole thing being measured.
            var accepted = streamBridge.send("downstream", MessageBuilder.withPayload(event)
                    .setHeader(KafkaHeaders.KEY, event.partitionKey().getBytes(StandardCharsets.UTF_8))
                    .build());
            if (!accepted) {
                throw new IllegalStateException("the broker refused " + event.partitionKey());
            }
        });
        var millis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.settled())
                .as("every row must settle, or the two measurements are not of the same work")
                .isEqualTo(MESSAGES);
        orchestrator.close();
        orchestrator = null;
        return Math.max(1, millis);
    }

    /**
     * One pending row per process, so every message is its own partition key.
     *
     * <p>That is the shape this is about: the batching win comes from how many <em>keys</em> can be
     * in flight, and a batch concentrated on a few processes could not be sent concurrently without
     * breaking their order. A saturated engine has thousands of processes in flight, so a batch
     * spread across keys is the realistic case, not a favourable one.
     */
    private void seed(OutboxMessageEntityRepository outbox) {
        var rows = new ArrayList<OutboxMessageEntity>(MESSAGES);
        for (var i = 0; i < MESSAGES; i++) {
            rows.add(new OutboxMessageEntity(new ProcessStatusChanged(
                    "p-" + i, "bk-" + i, "a process", "wd-1", 1, "RUNNING", 50,
                    LocalDateTime.now(), LocalDateTime.now(), null, LocalDateTime.now(), null)));
        }
        outbox.saveAll(rows);
    }
}
