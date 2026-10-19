package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.awaitility.Awaitility;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-37 — a parallel {@code forEach} under contention loses nothing.
 *
 * <p>The benchmark's fan-out at reduced scale: many processes, each a {@code forEach} over 50 items,
 * {@code mode: parallel}, {@code maxConcurrency: 10}, against a worker that answers at once. Ten
 * iterations of one process complete together, so the pod's {@code consumeUpstream} (the replies) and
 * {@code consumeOutbox} (the engine's own next hops) write the same process at the same instant, and
 * the loser fails with an optimistic-lock conflict or a PostgreSQL deadlock — a retryable failure the
 * engine rethrows so Kafka redelivers it.
 *
 * <p>It used not to be redelivered. Spring Kafka's default batch error handler retried the batch nine
 * times with no back-off and then discarded it ({@code FallbackBatchErrorHandler: Records discarded}),
 * and 12–19% of the benchmark's processes stopped for good with iterations left PENDING. Every process
 * completing is the assertion: a discarded reply or step event is a process that never does.
 */
class Dist37ParallelForEachContentionTest extends AbstractDistTest {

    static final int PROCESSES = Integer.getInteger("dist37.processes", 100);
    static final int ITEMS = 50;

    static ConfigurableApplicationContext orchestrator;

    /** Spring Kafka's own error handlers log here, at ERROR, when they give up on records. */
    static final ListAppender<ILoggingEvent> kafkaLog = new ListAppender<>();

    @BeforeAll
    static void startPods() {
        kafkaLog.start();
        ((Logger) LoggerFactory.getLogger("org.springframework.kafka")).addAppender(kafkaLog);
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        ((Logger) LoggerFactory.getLogger("org.springframework.kafka")).detachAppender(kafkaLog);
        orchestrator.close();
    }

    @Test
    void everyProcessOfAParallelForEachCompletesUnderContention() {
        var items = IntStream.rangeClosed(1, ITEMS).mapToObj(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        var run = "dist37-" + System.nanoTime() + "-";
        for (int i = 0; i < PROCESSES; i++) {
            createProcess("dist-fanout", run + i, new Variable("items", items));
        }

        Awaitility.await("all " + PROCESSES + " fan-out processes complete")
                .atMost(Duration.ofMinutes(4))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> completed(run), done -> done == PROCESSES);

        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM step_execution_entity s JOIN process_entity p ON p.id = s.process_id"
                        + " WHERE p.business_key LIKE ? AND s.parent_execution_id IS NOT NULL"
                        + " AND s.status <> 'COMPLETED'", Integer.class, run + "%"))
                .as("iterations left unfinished")
                .isZero();
        // Completing is not enough on its own: a discarded record whose process happened to be
        // moved on by a later event still means the consumer threw work away.
        assertThat(kafkaLog.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("discarded") || m.contains(" exhausted for ")))
                .as("records the Kafka error handler gave up on")
                .isEmpty();
    }

    private static int completed(String run) {
        return DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM process_entity WHERE business_key LIKE ? AND status = 'COMPLETED'",
                Integer.class, run + "%");
    }
}
