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

    @Autowired io.mateu.workflow.application.usecases.process.operate.CompleteStepManuallyUseCase completeStepManually;

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

    @Test
    void eachForEachIterationIsItsOwnHttpTask() throws Exception {
        createProcess("http-worker-foreach", "hw-7", new Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        var tasks = http.activate("py-1", "http-payments", 10, 30_000, 2_000).path("tasks");
        assertThat(tasks).hasSize(3);
        var ids = new java.util.HashSet<String>();
        var rooms = new java.util.ArrayList<String>();
        tasks.forEach(task -> {
            ids.add(task.path("id").asText());
            rooms.add(task.path("variables").path("room").asText());
        });
        assertThat(ids).hasSize(3);
        assertThat(rooms).containsExactlyInAnyOrder("101", "102", "103");
        assertThat(process("hw-7").getStatus()).isEqualTo(ProcessStatus.RUNNING);

        for (var task : tasks) {
            assertThat(http.complete(task, Map.of("reservationId", "R-" + task.path("variables").path("room").asText())))
                    .isEqualTo(204);
        }

        assertThat(process("hw-7").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(processVariable("hw-7", "reservations")).isEqualTo("[\"R-101\",\"R-102\",\"R-103\"]");
    }

    @Test
    void completingAStepManuallyInvalidatesTheLeasedTaskAndTheWorkersLateReplyIsRefused() throws Exception {
        worker.on("ship", TestWorker.succeed());
        createProcess("http-worker", "hw-8");
        var task = http.next("py-1", "http-payments", 2_000);

        completeStepManually.handle(new io.mateu.workflow.application.usecases.process.operate.CompleteStepManuallyCommand(
                process("hw-8").getId(), task.path("id").asText(),
                List.of(new io.mateu.workflow.domain.aggregates.Variable("chargeId", "manual")), "paid by phone", "ops"));
        assertThat(process("hw-8").getStatus()).isEqualTo(ProcessStatus.COMPLETED);

        // The worker that still holds the task hears it on its heartbeat…
        var beat = http.heartbeat(task, 60_000);
        assertThat(HttpWorkerClient.json(beat).path("cancelled").asBoolean()).isTrue();
        // …and a reply sent anyway never reaches the process.
        assertThat(http.complete(task, Map.of("chargeId", "late"))).isEqualTo(404);
        assertThat(processVariable("hw-8", "chargeId")).isEqualTo("manual");
    }

    @Test
    void aLateCompleteWithoutAHeartbeatAfterAManualCompletionIs409Cancelled() throws Exception {
        worker.on("ship", TestWorker.succeed());
        createProcess("http-worker", "hw-9");
        var task = http.next("py-1", "http-payments", 2_000);

        completeStepManually.handle(new io.mateu.workflow.application.usecases.process.operate.CompleteStepManuallyCommand(
                process("hw-9").getId(), task.path("id").asText(), List.of(), "skipped", "ops"));

        var late = http.completeResult(task, Map.of("chargeId", "late"));
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(HttpWorkerClient.json(late).path("reason").asText()).isEqualTo("CANCELLED");
        assertThat(processVariable("hw-9", "chargeId")).isNull();
        assertThat(process("hw-9").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Autowired io.mateu.workflow.application.services.BatchOperationService batches;

    /**
     * A batch acts on a process whose step is leased to an HTTP worker exactly as the single operation
     * does: a CANCEL batch cancels the task (the worker hears it on its heartbeat), and a RETRY_FROM_STEP
     * batch re-dispatches the step as a new attempt, so the worker's reply to the old one is refused.
     */
    @Test
    void batchesCancelOrRedispatchTheTasksLeasedToHttpWorkers() throws Exception {
        createProcess("http-worker", "hwb-1");
        createProcess("http-worker", "hwb-2");
        var leased = http.activate("py-1", "http-payments", 10, 60_000, 2_000).path("tasks");
        assertThat(leased).hasSize(2);
        com.fasterxml.jackson.databind.JsonNode first = null;
        com.fasterxml.jackson.databind.JsonNode second = null;
        for (var task : leased) {
            if (task.path("processId").asText().equals(process("hwb-1").getId())) first = task; else second = task;
        }
        var cancelTask = first;
        var retryTask = second;

        var retry = batches.create(io.mateu.workflow.domain.batch.BatchOperationType.RETRY_FROM_STEP,
                new io.mateu.workflow.domain.batch.BatchOperationFilter("http-worker", null, null, null, null, "hwb-2",
                        null, false, null),
                io.mateu.workflow.domain.batch.BatchOperationParameters.of(null, null, "charge", "gateway reset"), null);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(batches.get(retry.id()).orElseThrow()
                .status()).isEqualTo(io.mateu.workflow.domain.batch.BatchOperationStatus.COMPLETED));
        assertThat(batches.get(retry.id()).orElseThrow().succeeded()).isEqualTo(1);
        var again = http.next("py-2", "http-payments", 10_000);
        assertThat(again.path("id").asText()).isEqualTo(retryTask.path("id").asText());
        assertThat(again.path("attempt").asInt()).isGreaterThan(retryTask.path("attempt").asInt());
        assertThat(http.completeWithAttempt(retryTask.path("id").asText(), retryTask.path("attempt").asInt()))
                .as("the worker's reply to the attempt the batch replaced").isEqualTo(409);

        var cancel = batches.create(io.mateu.workflow.domain.batch.BatchOperationType.CANCEL,
                new io.mateu.workflow.domain.batch.BatchOperationFilter("http-worker", null, null, null, null, "hwb-",
                        null, false, null), null, null);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(batches.get(cancel.id()).orElseThrow()
                .status()).isEqualTo(io.mateu.workflow.domain.batch.BatchOperationStatus.COMPLETED));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(process("hwb-1").getStatus()).isEqualTo(ProcessStatus.CANCELLED));
        assertThat(HttpWorkerClient.json(http.heartbeat(cancelTask, 60_000)).path("cancelled").asBoolean()).isTrue();
        assertThat(http.complete(cancelTask, Map.of())).as("the task is gone once the worker heard").isEqualTo(404);
        assertThat(HttpWorkerClient.json(http.heartbeat(again, 60_000)).path("cancelled").asBoolean()).isTrue();
    }

    private String processVariable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value).findFirst().orElse(null);
    }
}
