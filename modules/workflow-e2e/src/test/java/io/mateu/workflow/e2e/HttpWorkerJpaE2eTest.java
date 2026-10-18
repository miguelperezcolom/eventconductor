package io.mateu.workflow.e2e;

import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskWaiters;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.HttpWorkerClient;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.config.HttpWorkersProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Workers over HTTP on the durable path (embedded + JPA on H2): the task is queued in
 * {@code http_task} inside the outbox relay's transaction, handed out by a long-poll, redelivered
 * when its lease expires, and the worker's answer moves the process on through the relay.
 */
@TestPropertySource(properties = {
        "workflow.workers.http.enabled=true",
        "workflow.workers.http.topics=http-payments",
        "workflow.workers.http.poll-interval=100ms",
})
class HttpWorkerJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired HttpTaskService service;
    @Autowired HttpTaskWaiters waiters;
    @Autowired HttpWorkersProperties properties;
    @Autowired JdbcTemplate jdbc;

    HttpWorkerClient http;

    @BeforeEach
    void client() {
        http = new HttpWorkerClient(service, waiters, properties);
    }

    @Test
    void anActionIsServedOverHttpThroughTheDurableQueue() throws Exception {
        worker.on("ship", TestWorker.succeed());
        createProcess("http-worker", "hwj-1", new Variable("amount", "7"));

        var task = http.next("py-1", "http-payments", 10_000);
        assertThat(task.path("variables").path("amount").asText()).isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM http_task", Long.class)).isEqualTo(1);

        assertThat(http.complete(task, Map.of("chargeId", "ch-7"))).isEqualTo(204);

        awaitStatus("hwj-1", ProcessStatus.COMPLETED);
        assertThat(step("hwj-1", "charge").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM http_task", Long.class)).isZero();
    }

    @Test
    void aTaskWhoseWorkerVanishedIsRedeliveredWhenItsLeaseExpires() throws Exception {
        worker.on("ship", TestWorker.succeed());
        createProcess("http-worker", "hwj-2");

        var abandoned = http.activate("crashed", "http-payments", 1, 1_000, 10_000).path("tasks").get(0);
        assertThat(http.activate("py-2", "http-payments", 1, 30_000, 200).path("tasks"))
                .as("still leased").isEmpty();

        var redelivered = http.next("py-2", "http-payments", 10_000);
        assertThat(redelivered.path("id").asText()).isEqualTo(abandoned.path("id").asText());
        assertThat(redelivered.path("delivery").asInt()).isEqualTo(2);
        assertThat(redelivered.path("attempt").asInt()).isEqualTo(abandoned.path("attempt").asInt());

        assertThat(http.complete(redelivered, Map.of())).isEqualTo(204);
        awaitStatus("hwj-2", ProcessStatus.COMPLETED);
        // The crashed worker comes back and answers: the task is long gone.
        assertThat(http.complete(abandoned, Map.of())).isEqualTo(404);
    }

    @Test
    void aFailureOverHttpFailsTheStepThroughTheRelay() throws Exception {
        createProcess("http-worker", "hwj-3");
        var task = http.next("py-1", "http-payments", 10_000);

        assertThat(http.fail(task, "INVALID", "no", false)).isEqualTo(204);

        awaitStatus("hwj-3", ProcessStatus.ERROR);
        await().untilAsserted(() -> assertThat(step("hwj-3", "charge").getStatus()).isEqualTo(StepExecutionStatus.ERROR));
    }
}
