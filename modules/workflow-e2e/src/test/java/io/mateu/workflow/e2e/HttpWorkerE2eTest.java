package io.mateu.workflow.e2e;

import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskWaiters;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.HttpWorkerClient;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.config.HttpWorkersProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Workers over HTTP, memory mode: a workflow whose ACTION on {@code http-payments} is served by a
 * worker that only speaks the HTTP worker API, while its other ACTION still goes to the embedded
 * worker — side by side, as the two transports are meant to run.
 */
@TestPropertySource(properties = {
        "workflow.workers.http.enabled=true",
        "workflow.workers.http.topics=http-payments",
        "workflow.workers.http.poll-interval=200ms",
})
class HttpWorkerE2eTest extends AbstractE2eTest {

    @Autowired HttpTaskService service;
    @Autowired HttpTaskWaiters waiters;
    @Autowired HttpWorkersProperties properties;

    HttpWorkerClient http;

    @BeforeEach
    void client() {
        http = new HttpWorkerClient(service, waiters, properties);
    }

    @Test
    void anActionIsServedByAnHttpWorkerAndTheNextOneByTheEmbeddedWorker() throws Exception {
        worker.on("ship", TestWorker.succeed());

        createProcess("http-worker", "hw-1", new Variable("amount", "42"));
        assertThat(step("hw-1", "charge").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(worker.invocationsOf("charge")).as("the embedded worker never sees an HTTP task").isZero();

        var task = http.next("py-1", "http-payments", 2_000);
        assertThat(task.path("stepId").asText()).isEqualTo("charge");
        assertThat(task.path("processId").asText()).isEqualTo(process("hw-1").getId());
        assertThat(task.path("variables").path("amount").asText()).isEqualTo("42");

        assertThat(http.complete(task, Map.of("chargeId", "ch-1", "fee", 1.5))).isEqualTo(204);

        assertThat(process("hw-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        var shipped = worker.received().stream().filter(r -> r.stepId().equals("ship")).findFirst().orElseThrow();
        assertThat(shipped.variables()).contains(new io.mateu.workflow.dtos.Variable("chargeId", "ch-1"),
                new io.mateu.workflow.dtos.Variable("fee", "1.5"));
    }

    @Test
    void aRetryableFailureIsRetriedAsANewAttemptAndALateReplyToTheOldOneIsRefused() throws Exception {
        worker.on("ship", TestWorker.succeed());
        createProcess("http-worker", "hw-2");

        var first = http.next("py-1", "http-payments", 2_000);
        assertThat(http.fail(first, "GATEWAY_DOWN", "try later", true)).isEqualTo(204);

        var second = http.next("py-1", "http-payments", 10_000);
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(second.path("attempt").asInt()).isGreaterThan(first.path("attempt").asInt());

        assertThat(http.completeWithAttempt(first.path("id").asText(), first.path("attempt").asInt()))
                .as("a reply for the abandoned attempt").isEqualTo(409);
        assertThat(http.complete(second, Map.of())).isEqualTo(204);
        assertThat(process("hw-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(errorsOf("hw-2")).anySatisfy(error -> assertThat(error).contains("GATEWAY_DOWN: try later"));
    }

    @Test
    void aNonRetryableFailureFailsTheStepAtOnce() throws Exception {
        createProcess("http-worker", "hw-3");

        var task = http.next("py-1", "http-payments", 2_000);
        assertThat(http.fail(task, "CARD_INVALID", "the card number is wrong", false)).isEqualTo(204);

        assertThat(step("hw-3", "charge").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("hw-3").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(http.activate("py-1", "http-payments", 1, 30_000, 300).path("tasks")).isEmpty();
    }

    @Test
    void cancellingTheProcessReachesTheWorkerThroughItsHeartbeat() throws Exception {
        createProcess("http-worker", "hw-4");
        var task = http.next("py-1", "http-payments", 2_000);
        assertThat(HttpWorkerClient.json(http.heartbeat(task, 60_000)).path("cancelled").asBoolean()).isFalse();

        cancelProcessUseCase.handle(new CancelProcessCommand(process("hw-4").getId()));

        var beat = http.heartbeat(task, 60_000);
        assertThat(beat.getResponse().getStatus()).isEqualTo(200);
        assertThat(HttpWorkerClient.json(beat).path("cancelled").asBoolean()).isTrue();
        assertThat(http.complete(task, Map.of())).as("the task is gone once the worker heard").isEqualTo(404);
        assertThat(process("hw-4").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
    }

    @Test
    void aWaitingWorkerIsHandedTheTaskAsSoonAsTheProcessReachesIt() throws Exception {
        worker.on("ship", TestWorker.succeed());
        var waiting = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return http.next("py-1", "http-payments", 10_000);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> waiters.waitingCount() == 1);

        createProcess("http-worker", "hw-5");

        var task = waiting.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(task.path("processId").asText()).isEqualTo(process("hw-5").getId());
        http.complete(task, Map.of());
        assertThat(process("hw-5").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aDynamicStepServedOverHttpInjectsItsSteps() throws Exception {
        worker.on("extra", TestWorker.succeed());
        createProcess("http-worker-dynamic", "hw-6");

        var task = http.next("py-1", "http-payments", 2_000);
        assertThat(http.inject(task, List.of(Map.of("id", "extra", "type", "ACTION", "name", "Extra",
                "topic", "work", "preconditionStepId", "plan")))).isEqualTo(204);
        assertThat(http.complete(task, Map.of())).isEqualTo(204);

        assertThat(worker.invocationsOf("extra")).isEqualTo(1);
        assertThat(process("hw-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
