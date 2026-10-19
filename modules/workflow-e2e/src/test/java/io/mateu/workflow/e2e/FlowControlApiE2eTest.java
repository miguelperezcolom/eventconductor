package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.ProcessesHttp;
import io.mateu.workflow.e2e.support.SyncInvocationHttp;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A definition's startPolicy at the API doors (memory mode, over HTTP): POST /workflow/api/processes answers
 * 202 for a debounced start, 429 with Retry-After for a dropped one, 200 with the running process for a
 * skipped one; a synchronous invocation is refused (SINGLETON_RUNNING, THROTTLED) instead of being held back.
 */
class FlowControlApiE2eTest extends AbstractE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired ProcessOperationsService processOperationsService;
    @Autowired SyncInvocationService syncInvocationService;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;

    @Test
    void aDebouncedStartIsAcceptedThenStartsOnceWithTheLastData() throws Exception {
        var http = new ProcessesHttp(processOperationsService);
        worker.on("api-score", TestWorker.succeed());
        for (int i = 1; i <= 3; i++) {
            var held = http.start("{\"definitionId\": \"api-debounced\", \"businessKey\": \"score-c1-" + i + "\","
                    + " \"variables\": {\"customerId\": \"c1\", \"edit\": \"" + i + "\"}}");
            assertThat(held.getStatus()).isEqualTo(202);
            assertThat(held.getHeader("EventConductor-Flow-Control")).isEqualTo("debounced");
            assertThat(ProcessesHttp.json(held).path("businessKey").asText()).isEqualTo("score-c1-" + i);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(processRepository.findByBusinessKey("score-c1-3")).isPresent());
        assertThat(process("score-c1-3").getVariables()).anyMatch(v -> "edit".equals(v.name()) && "3".equals(v.value()));
        assertThat(processRepository.findByBusinessKey("score-c1-1")).isEmpty();
        assertThat(processRepository.findByBusinessKey("score-c1-2")).isEmpty();
    }

    @Test
    void aThrottleThatDropsAnswers429WithRetryAfter() throws Exception {
        var http = new ProcessesHttp(processOperationsService);
        worker.on("api-export", TestWorker.succeed());
        assertThat(http.start("{\"definitionId\": \"api-throttled\", \"businessKey\": \"export-t1-1\","
                + " \"variables\": {\"tenant\": \"t1\"}}").getStatus()).isEqualTo(201);
        var dropped = http.start("{\"definitionId\": \"api-throttled\", \"businessKey\": \"export-t1-2\","
                + " \"variables\": {\"tenant\": \"t1\"}}");
        assertThat(dropped.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(dropped.getHeader("Retry-After"))).isBetween(3000L, 3600L);
        assertThat(processRepository.findByBusinessKey("export-t1-2")).isEmpty();
        // Another key has a window of its own; a retry of the started one is still 200, not a throttle hit.
        assertThat(http.start("{\"definitionId\": \"api-throttled\", \"businessKey\": \"export-t2-1\","
                + " \"variables\": {\"tenant\": \"t2\"}}").getStatus()).isEqualTo(201);
        assertThat(http.start("{\"definitionId\": \"api-throttled\", \"businessKey\": \"export-t1-1\","
                + " \"variables\": {\"tenant\": \"t1\"}}").getStatus()).isEqualTo(200);
        // A key that does not render refuses the start.
        assertThat(http.start("{\"definitionId\": \"api-throttled\", \"businessKey\": \"export-none\"}").getStatus())
                .isEqualTo(400);
    }

    @Test
    void aSingletonThatSkipsAnswersWithTheRunningProcess() throws Exception {
        var http = new ProcessesHttp(processOperationsService);
        worker.on("api-reconcile", TestWorker.deferForever());
        var first = http.start("{\"definitionId\": \"api-singleton\", \"businessKey\": \"rec-a1-1\","
                + " \"variables\": {\"account\": \"a1\"}}");
        assertThat(first.getStatus()).isEqualTo(201);
        var firstId = ProcessesHttp.json(first).path("id").asText();
        var skipped = http.start("{\"definitionId\": \"api-singleton\", \"businessKey\": \"rec-a1-2\","
                + " \"variables\": {\"account\": \"a1\"}}");
        assertThat(skipped.getStatus()).isEqualTo(200);
        assertThat(skipped.getHeader("EventConductor-Flow-Control")).isEqualTo("skipped");
        assertThat(ProcessesHttp.json(skipped).path("id").asText()).isEqualTo(firstId);
        assertThat(processRepository.findByBusinessKey("rec-a1-2")).isEmpty();

        cancelProcessUseCase.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(firstId));
        assertThat(processRepository.findById(firstId).orElseThrow().getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(http.start("{\"definitionId\": \"api-singleton\", \"businessKey\": \"rec-a1-3\","
                + " \"variables\": {\"account\": \"a1\"}}").getStatus()).isEqualTo(201);
    }

    @Test
    void aSynchronousInvocationIsRefusedRatherThanHeldBack() throws Exception {
        var http = new SyncInvocationHttp(syncInvocationService, waiters, metrics);
        worker.on("sfc-quote", TestWorker.deferForever());

        var first = http.invoke("sync-flow-controlled", "sfc-1", "wait=0", "{\"businessKey\": \"sfc-1\","
                + " \"variables\": {\"customer\": \"c9\"}}");
        assertThat(first.getResponse().getStatus()).isIn(200, 202);
        var busy = http.invoke("sync-flow-controlled", "sfc-2", "wait=0", "{\"businessKey\": \"sfc-2\","
                + " \"variables\": {\"customer\": \"c9\"}}");
        assertThat(busy.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(busy).path("reason").asText()).isEqualTo("SINGLETON_RUNNING");

        worker.on("sfc-quote", TestWorker.succeed(var("price", "10")));
        var other = http.invoke("sync-flow-controlled", "sfc-3", null, "{\"businessKey\": \"sfc-3\","
                + " \"variables\": {\"customer\": \"c8\"}}");
        assertThat(other.getResponse().getStatus()).isEqualTo(200);
        var again = http.invoke("sync-flow-controlled", "sfc-4", null, "{\"businessKey\": \"sfc-4\","
                + " \"variables\": {\"customer\": \"c8\"}}");
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        var throttled = http.invoke("sync-flow-controlled", "sfc-5", null, "{\"businessKey\": \"sfc-5\","
                + " \"variables\": {\"customer\": \"c8\"}}");
        assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(throttled).path("reason").asText()).isEqualTo("THROTTLED");
        assertThat(processRepository.findByBusinessKey("sfc-5")).isEmpty();
    }

    private static JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
