package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskReplySink;
import io.mateu.workflow.worker.http.HostGuard;
import io.mateu.workflow.worker.http.HttpCallHandler;
import io.mateu.workflow.worker.http.HttpWorkerAutoConfiguration;
import io.mateu.workflow.worker.http.HttpWorkerProperties;
import io.mateu.workflow.worker.http.SecretResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP_CALL end to end: the engine renders the request, the real built-in http-call task (worker-http,
 * run through the worker runtime's TaskDispatcher as worker-embedded does) calls a local server, and the
 * response lands in the process — or, for a 4xx, the step fails at once, unretried, and the saga rolls back.
 */
class HttpCallE2eTest extends AbstractE2eTest {

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 201;
    TaskDispatcher dispatcher;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/pay/charges/", exchange -> {
            calls.incrementAndGet();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var response = status / 100 == 2 ? "{\"id\":\"ch_" + exchange.getRequestURI().getPath().substring("/pay/charges/".length())
                    + "\",\"echo\":" + body + "}" : "{\"error\":\"card declined\"}";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Charged-At", "2026-09-24T12:00:00Z");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var properties = new HttpWorkerProperties();
        var payments = new HttpWorkerProperties.Connection();
        payments.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/pay");
        properties.getConnections().put("payments", payments);
        var handler = new HttpCallHandler(properties, new SecretResolver(name -> null),
                new HostGuard(List.of(), InetAddress::getAllByName), null);
        var sink = new TaskReplySink() {
            public void running(TaskExecutionRequested task) { }
            public void completed(TaskExecutionRequested task, List<Variable> variables) {
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(task.taskExecutionId(),
                        variables.stream().map(v -> new io.mateu.workflow.domain.aggregates.Variable(v.name(), v.value())).toList(),
                        "", StepExecutionStatus.COMPLETED));
            }
            public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(task.taskExecutionId(),
                        List.of(), reason, StepExecutionStatus.ERROR));
            }
        };
        io.mateu.workflow.worker.api.TaskRegistration<?, ?> registration =
                new HttpWorkerAutoConfiguration().httpCallTaskRegistration(handler, properties);
        dispatcher = new TaskDispatcher(new TaskRegistry(List.of(registration)),
                sink, Cancellations.NONE, new ObjectMapper(), true);
        worker.on("charge", (request, callback, invocation) -> dispatcher.dispatch(request));
        worker.on("book", TestWorker.succeed());
        worker.on("unbook", TestWorker.succeed());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theResponseLandsInTheProcessAndTheReplyUsesIt() {
        createProcess("http-call-saga", "hc-1", new Variable("bookingId", "B-1"), new Variable("amount", "40"));

        var process = process("hc-1");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process.getVariables()).extracting("name", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("chargeId", "ch_B-1"),
                        org.assertj.core.groups.Tuple.tuple("chargedAt", "2026-09-24T12:00:00Z"));
        assertThat(process.getReply().payload()).isEqualTo("{\"bookingId\":\"B-1\",\"chargeId\":\"ch_B-1\"}");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aClientErrorIsNotRetriedAndTheSagaRollsBack() {
        status = 402;

        createProcess("http-call-saga", "hc-2", new Variable("bookingId", "B-2"), new Variable("amount", "40"));

        assertThat(calls.get()).as("retries: 2, but a 402 is final").isEqualTo(1);
        assertThat(step("hc-2", "charge").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(step("hc-2", "unbook").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process("hc-2").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(errorsOf("hc-2")).anyMatch(error -> error.contains("HTTP_402") && error.contains("card declined"));
    }

    @Test
    void aServerErrorSpendsTheRetries() {
        status = 503;

        createProcess("http-call-saga", "hc-3", new Variable("bookingId", "B-3"), new Variable("amount", "40"));

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(calls.get()).isEqualTo(3));
    }
}
