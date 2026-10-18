package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.e2e.support.E2eConfig;
import io.mateu.workflow.e2e.support.E2eTestApplication;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Workers over HTTP through a real servlet container: the controller is the application's own (not a
 * hand-built one), so the JSON a non-Java worker sends and receives goes through the application's
 * message converters, the long-poll through Tomcat's async support, and the key through the
 * engine's configured credentials.
 *
 * <p>The second test runs the <b>Python SDK</b> against this engine (sdks/python/eventconductor-worker,
 * its {@code test_engine_integration.py}) when a Python interpreter is given with
 * {@code -Dworkflow.tests.python=/path/to/python} (with pytest installed); skipped otherwise.
 */
@SpringBootTest(classes = E2eTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=servlet",
                "spring.autoconfigure.exclude=",
                "workflow.workers.http.enabled=true",
                "workflow.workers.http.topics=http-payments",
                "workflow.workers.http.api-keys.e2e=wire-key",
        })
@Import(E2eConfig.class)
class HttpWorkerOverTheWireE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired TestWorker worker;
    @Autowired ProcessUpstreamEventUseCase upstream;
    @Autowired ProcessRepository processes;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/workflow/api/workers" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("X-Api-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start(String businessKey) {
        upstream.handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested("http-worker", businessKey,
                List.of(new Variable("amount", "9")))));
    }

    private ProcessStatus status(String businessKey) {
        return processes.findByBusinessKey(businessKey).orElseThrow().getStatus();
    }

    @Test
    void aWorkerOnTheWireLongPollsCompletesAndIsRefusedWithoutItsKey() throws Exception {
        worker.on("ship", TestWorker.succeed());
        assertThat(post("/activate", null, "{\"workerId\":\"w\",\"topics\":[\"http-payments\"]}").statusCode())
                .isEqualTo(401);

        // A long-poll that is waiting when the process reaches the step.
        var poll = http.sendAsync(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/workflow/api/workers/activate"))
                .header("Content-Type", "application/json").header("X-Api-Key", "wire-key")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"workerId\":\"wire\",\"topics\":[\"http-payments\"],\"maxTasks\":1,\"leaseMs\":30000,\"waitMs\":10000}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(300);
        start("wire-1");

        var activation = poll.get(15, TimeUnit.SECONDS);
        assertThat(activation.statusCode()).isEqualTo(200);
        JsonNode task = JSON.readTree(activation.body()).path("tasks").get(0);
        assertThat(task.path("variables").path("amount").asText()).isEqualTo("9");

        var beat = post("/tasks/" + task.path("id").asText() + "/heartbeat", "wire-key",
                "{\"leaseToken\":\"" + task.path("leaseToken").asText() + "\",\"extendMs\":30000}");
        assertThat(beat.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(beat.body()).path("cancelled").asBoolean()).isFalse();

        var stale = post("/tasks/" + task.path("id").asText() + "/complete", "wire-key",
                "{\"attempt\": 99, \"variables\": {}}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(stale.body()).path("reason").asText()).isEqualTo("STALE_ATTEMPT");

        var done = post("/tasks/" + task.path("id").asText() + "/complete", "wire-key",
                "{\"attempt\": " + task.path("attempt").asInt() + ", \"variables\": {\"chargeId\": \"ch-9\", \"n\": 3}}");
        assertThat(done.statusCode()).isEqualTo(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> status("wire-1") == ProcessStatus.COMPLETED);
    }

    @Test
    void thePythonSdkServesATaskOfThisEngine() throws Exception {
        var python = System.getProperty("workflow.tests.python");
        assumeTrue(python != null && !python.isBlank() && new File(python).canExecute(),
                "Set -Dworkflow.tests.python=/path/to/python (with pytest) to run the Python SDK against the engine");
        var sdk = Path.of("..", "..", "sdks", "python", "eventconductor-worker").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(sdk), "Python SDK not found at " + sdk);
        worker.on("ship", TestWorker.succeed());
        start("wire-py");

        var process = new ProcessBuilder(python, "-m", "pytest", "-q", "tests/test_engine_integration.py")
                .directory(sdk.toFile())
                .redirectErrorStream(true);
        process.environment().put("EC_ENGINE_URL", "http://localhost:" + port);
        process.environment().put("EC_API_KEY", "wire-key");
        process.environment().put("EC_TOPIC", "http-payments");
        process.environment().put("EC_WAIT_SECONDS", "30");
        var running = process.start();
        var output = new String(running.getInputStream().readAllBytes());
        assertThat(running.waitFor(90, TimeUnit.SECONDS)).isTrue();
        assertThat(running.exitValue()).as(output).isZero();
        assertThat(output).contains("1 passed");

        await().atMost(Duration.ofSeconds(10)).until(() -> status("wire-py") == ProcessStatus.COMPLETED);
        assertThat(processes.findByBusinessKey("wire-py").orElseThrow().getVariables())
                .anySatisfy(variable -> {
                    assertThat(variable.name()).isEqualTo("servedBy");
                    assertThat(variable.value()).isEqualTo("python");
                });
    }
}
