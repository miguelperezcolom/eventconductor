package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.SyncInvocationHttp;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over HTTP (Spring MVC, memory mode): start a process, wait, receive what its REPLY
 * step answered — and the edges: an early reply, a deadline that passes first, idempotent retries,
 * and the refusals.
 */
class SyncInvocationE2eTest extends AbstractE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired SyncInvocationService service;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;

    SyncInvocationHttp http;

    @BeforeEach
    void http() {
        http = new SyncInvocationHttp(service, waiters, metrics);
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void theCallerReceivesTheReplyTheProcessGaveAtTheEnd() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-1")));

        var result = http.invoke("sync-reply-end", "k-end-1", null,
                "{\"businessKey\": \"sync-end-1\", \"variables\": {\"bookingId\": \"B-1\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = body(result);
        assertThat(body.path("outcome").asText()).isEqualTo("REPLIED");
        assertThat(body.path("reply").path("bookingId").asText()).isEqualTo("B-1");
        assertThat(body.path("reply").path("confirmation").asText()).isEqualTo("C-1");
        assertThat(body.path("processStatus").asText()).isEqualTo("COMPLETED");
        assertThat(process("sync-end-1").getId()).isEqualTo(body.path("processId").asText());
    }

    @Test
    void anEarlyReplyAnswersWhileTheProcessCarriesOn() throws Exception {
        worker.on("notify", TestWorker.deferForever());

        var result = http.invoke("sync-reply-early", "k-early-1", null,
                "{\"businessKey\": \"sync-early-1\", \"variables\": {\"bookingId\": \"B-2\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = body(result);
        assertThat(body.path("reply").path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("processStatus").asText()).isEqualTo("RUNNING");
        assertThat(step("sync-early-1", "notify").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void aDeadlineThatPassesFirstAnswers202AndTheReplyIsThereLater() throws Exception {
        worker.on("book", TestWorker.deferForever());

        var first = http.invoke("sync-reply-end", "k-late-1", "wait=0",
                "{\"businessKey\": \"sync-late-1\", \"variables\": {\"bookingId\": \"B-3\"}}");

        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        var location = first.getResponse().getHeader("Location");
        assertThat(location).startsWith("/workflow/api/invocations/");
        assertThat(body(first).has("reply")).isFalse();
        assertThat(process("sync-late-1").getStatus()).isEqualTo(ProcessStatus.RUNNING);

        // Still nothing: a GET that does not wait says so.
        assertThat(http.get(location, null).getResponse().getStatus()).isEqualTo(202);

        // The worker finishes; the process reaches its REPLY; the result URL now has it.
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(step("sync-late-1", "book").id(),
                List.of(new io.mateu.workflow.domain.aggregates.Variable("confirmation", "C-3")), "",
                StepExecutionStatus.COMPLETED));
        var later = http.get(location, "wait=5");
        assertThat(later.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(later).path("reply").path("confirmation").asText()).isEqualTo("C-3");
    }

    @Test
    void aRetryWithTheSameKeyGetsTheSameProcessAndTheSameReply() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-4")));
        var request = "{\"businessKey\": \"sync-retry-1\", \"variables\": {\"bookingId\": \"B-4\"}}";

        var first = body(http.invoke("sync-reply-end", "k-retry-1", null, request));
        var second = body(http.invoke("sync-reply-end", "k-retry-1", null, request));

        assertThat(second.path("invocationId").asText()).isEqualTo(first.path("invocationId").asText());
        assertThat(second.path("processId").asText()).isEqualTo(first.path("processId").asText());
        assertThat(second.path("reply")).isEqualTo(first.path("reply"));
        assertThat(worker.invocationsOf("book")).isEqualTo(1);

        var byKey = http.get("/workflow/api/definitions/sync-reply-end/invocations?idempotencyKey=k-retry-1", null);
        assertThat(body(byKey).path("processId").asText()).isEqualTo(first.path("processId").asText());
    }

    @Test
    void aKeyReusedForAnotherRequestIsRefused() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-5")));
        http.invoke("sync-reply-end", "k-reuse-1", null, "{\"variables\": {\"bookingId\": \"B-5\"}}");

        var reused = http.invoke("sync-reply-end", "k-reuse-1", null, "{\"variables\": {\"bookingId\": \"OTHER\"}}");

        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void theRefusals() throws Exception {
        assertThat(http.invoke("sync-reply-end", null, null, "{}").getResponse().getStatus())
                .as("no Idempotency-Key").isEqualTo(400);
        assertThat(http.invoke("sync-not-invocable", "k-x", null, "{}").getResponse().getStatus())
                .as("not sync-invocable").isEqualTo(409);
        assertThat(http.invoke("does-not-exist", "k-y", null, "{}").getResponse().getStatus())
                .as("unknown definition").isEqualTo(404);
        assertThat(http.get("/workflow/api/invocations/nope", null).getResponse().getStatus()).isEqualTo(404);

        worker.on("book", TestWorker.succeed(var("confirmation", "C-6")));
        http.invoke("sync-reply-end", "k-bk-1", null, "{\"businessKey\": \"sync-bk-taken\"}");
        assertThat(http.invoke("sync-reply-end", "k-bk-2", null, "{\"businessKey\": \"sync-bk-taken\"}")
                .getResponse().getStatus()).as("business key taken").isEqualTo(409);
    }

    @Test
    void nonStringVariablesArriveAsJsonText() throws Exception {
        worker.on("book", TestWorker.succeed());
        var result = http.invoke("sync-reply-end", "k-json-1", null,
                "{\"businessKey\": \"sync-json-1\", \"variables\": {\"bookingId\": 42}}");
        assertThat(body(result).path("reply").path("bookingId").asText()).isEqualTo("42");
    }
}
