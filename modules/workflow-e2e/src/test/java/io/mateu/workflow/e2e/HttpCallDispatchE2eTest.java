package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.worker.FailureMarkers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP_CALL, engine side: the request is rendered from the process state and dispatched to the
 * built-in http-call task (here, the programmable test worker stands in for it), and a failure the
 * worker declares final is not retried.
 */
class HttpCallDispatchE2eTest extends AbstractE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private void create(String businessKey) {
        createProcess("http-call-render", businessKey, new Variable("bookingId", "B-1"),
                new Variable("currency", "EUR"), new Variable("tenant", "acme"), new Variable("amount", "12.5"));
    }

    @Test
    void theRenderedRequestReachesTheHttpTask() throws Exception {
        worker.on("charge", TestWorker.succeed(TestWorker.var("chargeId", "ch_1")));

        create("http-1");

        var request = worker.received().stream().filter(r -> r.stepId().equals("charge")).findFirst().orElseThrow();
        assertThat(request.taskId()).isEqualTo("http-call@1");
        var http = request.variables().stream().filter(v -> v.name().equals("__http")).findFirst().orElseThrow().value();
        var rendered = JSON.readTree(http);
        assertThat(rendered.path("method").asText()).isEqualTo("POST");
        assertThat(rendered.path("connection").asText()).isEqualTo("payments");
        assertThat(rendered.path("path").asText()).isEqualTo("/charges/B-1");
        assertThat(rendered.path("query").path("currency").asText()).isEqualTo("EUR");
        assertThat(rendered.path("headers").path("X-Tenant").asText()).isEqualTo("acme");
        assertThat(JSON.readTree(rendered.path("body").asText()).path("amount").asDouble()).isEqualTo(12.5);
        assertThat(rendered.path("bodyKind").asText()).isEqualTo("json");
        assertThat(rendered.path("idempotencyKey").asText()).isEqualTo(step("http-1", "charge").id());
        assertThat(rendered.path("retryOn").toString()).isEqualTo("[\"5xx\",\"io\"]");
        assertThat(process("http-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void aFailureDeclaredFinalIsNotRetried() {
        worker.on("charge", TestWorker.failWith(FailureMarkers.NON_RETRYABLE + "HTTP_404: no such booking"));

        create("http-2");

        assertThat(worker.invocationsOf("charge")).as("retries: 2, but a final failure is not retried").isEqualTo(1);
        assertThat(step("http-2", "charge").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("http-2").getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void anOrdinaryFailureStillSpendsItsRetries() {
        worker.on("charge", TestWorker.failWith("HTTP_503: try later"));

        create("http-3");

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(worker.invocationsOf("charge")).isEqualTo(3));
    }
}
