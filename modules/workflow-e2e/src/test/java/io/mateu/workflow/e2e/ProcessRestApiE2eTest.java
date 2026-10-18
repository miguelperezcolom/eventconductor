package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.ProcessesHttp;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The processes REST API end to end on the in-memory engine: a process is started, read, listed,
 * retried after it fails and another cancelled mid-flight — all over HTTP, through the real use cases.
 */
class ProcessRestApiE2eTest extends AbstractE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired ProcessOperationsService processOperationsService;

    @Test
    void startsListsRetriesAndCancelsProcessesOverHttp() throws Exception {
        var http = new ProcessesHttp(processOperationsService);
        worker.on("flaky", TestWorker.fail());

        // Start: 201 with a Location, and the same key again is the same process (200).
        var started = http.start("{\"definitionId\": \"retry\", \"businessKey\": \"rest-mem-1\", \"variables\": {\"amount\": 12}}");
        assertThat(started.getStatus()).isEqualTo(201);
        var id = ProcessesHttp.json(started).path("id").asText();
        assertThat(started.getHeader("Location")).isEqualTo("/workflow/api/processes/" + id);
        var again = http.start("{\"definitionId\": \"retry\", \"businessKey\": \"rest-mem-1\"}");
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(ProcessesHttp.json(again).path("id").asText()).isEqualTo(id);
        assertThat(http.getWithoutKey("/" + id).getStatus()).isEqualTo(401);

        // It fails (retries exhausted) — read over HTTP, listed by status and definition.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(http.status(id)).isEqualTo("ERROR"));
        assertThat(ProcessesHttp.json(http.get("/" + id + "/variables")).path("amount").asText()).isEqualTo("12");
        var listed = ProcessesHttp.json(http.get("?definitionId=retry&status=ERROR&size=10&businessKey=rest-mem-1"));
        assertThat(listed.path("totalElements").asLong()).isEqualTo(1);
        assertThat(listed.path("content").get(0).path("businessKey").asText()).isEqualTo("rest-mem-1");
        var flaky = java.util.stream.StreamSupport.stream(ProcessesHttp.json(http.get("/" + id + "/steps")).spliterator(), false)
                .filter(step -> "flaky".equals(step.path("stepId").asText())).findFirst().orElseThrow();
        assertThat(flaky.path("status").asText()).isEqualTo("ERROR");

        // A process that is not stopped cannot be restarted; this one can be retried — and now succeeds.
        assertThat(http.post("/" + id + "/pause").getStatus()).isEqualTo(409);
        worker.on("flaky", TestWorker.succeed());
        assertThat(http.post("/" + id + "/retry").getStatus()).isEqualTo(202);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(http.status(id)).isEqualTo("COMPLETED"));
        assertThat(http.post("/" + id + "/retry").getStatus()).isEqualTo(409);

        // Cancel one in flight.
        worker.on("wait", TestWorker.deferForever());
        var inFlight = ProcessesHttp.json(http.start("{\"definitionId\": \"cancel\", \"businessKey\": \"rest-mem-2\"}")).path("id").asText();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(http.status(inFlight)).isEqualTo("RUNNING"));
        assertThat(http.post("/" + inFlight + "/cancel").getStatus()).isEqualTo(202);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(http.status(inFlight)).isEqualTo("CANCELLED"));
        assertThat(worker.invocationsOf("next")).isZero();

        assertThat(http.get("/no-such-process").getStatus()).isEqualTo(404);
        assertThat(ProcessesHttp.json(http.get("?q=rest-mem&size=10")).path("totalElements").asLong()).isEqualTo(2);
    }
}
