package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure contract end to end: what a synchronous caller hears when the process fails before
 * its REPLY — immediately (with the rollback still running) or after the rollback — when it is
 * cancelled, when it ends along a path without a REPLY, and when its lock is busy.
 */
class SyncInvocationFailureE2eTest extends AbstractE2eTest {

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

    private static String request(String businessKey, String bookingId) {
        return "{\"businessKey\": \"" + businessKey + "\", \"variables\": {\"bookingId\": \"" + bookingId + "\"}}";
    }

    private void complete(String businessKey, String stepId) {
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(step(businessKey, stepId).id(),
                List.of(), "", StepExecutionStatus.COMPLETED));
    }

    @Test
    void failingBeforeTheReplyAnswersAtOnceWhileTheRollbackRuns() throws Exception {
        worker.on("book", TestWorker.succeed());
        worker.on("pay", TestWorker.fail());
        worker.on("undo-book", TestWorker.deferForever());

        var result = http.invoke("sync-saga", "f-imm-1", "wait=5", request("sync-fail-imm-1", "B-F1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        var body = body(result);
        assertThat(body.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(body.path("compensation").asText()).isEqualTo("IN_PROGRESS");
        assertThat(body.path("error").asText()).contains("'pay'");
        assertThat(body.has("reply")).isFalse();
        assertThat(step("sync-fail-imm-1", "undo-book").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // The rollback finishes; the process becomes COMPENSATED, and the caller's answer stays the one it got.
        complete("sync-fail-imm-1", "undo-book");
        assertThat(process("sync-fail-imm-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        var later = body(http.get(body.path("location").asText(), null));
        assertThat(later.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(later.path("processStatus").asText()).isEqualTo("COMPENSATED");
    }

    @Test
    void afterCompensationTheCallerHearsOnceTheRollbackHasFinished() throws Exception {
        worker.on("book", TestWorker.succeed());
        worker.on("pay", TestWorker.fail());
        worker.on("undo-book", TestWorker.deferForever());

        var first = http.invoke("sync-saga-after", "f-after-1", "wait=0", request("sync-fail-after-1", "B-F2"));
        assertThat(first.getResponse().getStatus()).as("rollback still running").isEqualTo(202);
        assertThat(process("sync-fail-after-1").getStatus()).isEqualTo(ProcessStatus.ERROR);

        complete("sync-fail-after-1", "undo-book");
        var answer = http.get(body(first).path("location").asText(), "wait=5");
        assertThat(answer.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(answer).path("outcome").asText()).isEqualTo("COMPENSATED");
        assertThat(body(answer).path("compensation").asText()).isEqualTo("DONE");
        assertThat(body(answer).path("error").asText()).contains("'pay'");
    }

    @Test
    void aRollbackThatFailsIsReportedAsSuch() throws Exception {
        worker.on("book", TestWorker.succeed());
        worker.on("pay", TestWorker.fail());
        worker.on("undo-book", TestWorker.fail());

        var result = http.invoke("sync-saga-after", "f-after-2", "wait=5", request("sync-fail-after-2", "B-F3"));

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(result).path("outcome").asText()).isEqualTo("COMPENSATION_FAILED");
        assertThat(body(result).path("compensation").asText()).isEqualTo("FAILED");
    }

    @Test
    void failingWithNothingToUndoSaysSo() throws Exception {
        worker.on("book", TestWorker.fail());

        for (var definition : List.of("sync-saga", "sync-saga-after")) {
            var result = http.invoke(definition, "f-none-" + definition, "wait=5",
                    request("sync-fail-none-" + definition, "B-F4"));
            assertThat(result.getResponse().getStatus()).as(definition).isEqualTo(502);
            assertThat(body(result).path("outcome").asText()).as(definition).isEqualTo("FAILED");
            assertThat(body(result).path("compensation").asText()).as(definition).isEqualTo("NONE");
        }
    }

    @Test
    void aCancelledProcessAnswersCancelled() throws Exception {
        worker.on("book", TestWorker.deferForever());
        var first = http.invoke("sync-saga", "f-cancel-1", "wait=0", request("sync-cancel-1", "B-F5"));
        assertThat(first.getResponse().getStatus()).isEqualTo(202);

        cancelProcessUseCase.handle(new CancelProcessCommand(process("sync-cancel-1").getId()));

        var answer = http.get(body(first).path("location").asText(), "wait=5");
        assertThat(answer.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(answer).path("outcome").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void aRunThatEndsWithoutAReplyStillAnswers() throws Exception {
        var silent = http.invoke("sync-choice-silent", "f-silent-1", "wait=5",
                "{\"businessKey\": \"sync-silent-1\", \"variables\": {\"mode\": \"quiet\"}}");
        assertThat(silent.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(silent).path("outcome").asText()).isEqualTo("COMPLETED_WITHOUT_REPLY");
        assertThat(body(silent).has("reply")).isFalse();

        var replied = http.invoke("sync-choice-silent", "f-silent-2", "wait=5",
                "{\"businessKey\": \"sync-silent-2\", \"variables\": {\"mode\": \"reply\"}}");
        assertThat(body(replied).path("outcome").asText()).isEqualTo("REPLIED");
        assertThat(body(replied).path("reply").path("answered").asBoolean()).isTrue();
    }

    @Test
    void aBusyLockRefusesWithoutCreatingAnInstanceWhenTheDefinitionSaysFail() throws Exception {
        worker.on("work", TestWorker.deferForever());
        var holder = http.invoke("sync-lock-fail", "f-lock-1", "wait=0", request("sync-lock-holder", "B-L1"));
        assertThat(holder.getResponse().getStatus()).isEqualTo(202);

        var refused = http.invoke("sync-lock-fail", "f-lock-2", "wait=0", request("sync-lock-refused", "B-L1"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(processRepository.findByBusinessKey("sync-lock-refused")).isEmpty();
        assertThat(service.findByKey("sync-lock-fail", "f-lock-2")).as("the key stays free for the retry").isEmpty();

        var otherKey = http.invoke("sync-lock-fail", "f-lock-3", "wait=0", request("sync-lock-other", "B-L2"));
        assertThat(otherKey.getResponse().getStatus()).as("a different booking is not blocked").isEqualTo(202);

        // Once the holder finishes, the same booking can be invoked again.
        complete("sync-lock-holder", "work");
        worker.on("work", TestWorker.succeed());
        var retried = http.invoke("sync-lock-fail", "f-lock-2", "wait=5", request("sync-lock-refused", "B-L1"));
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aBusyLockQueuesByDefaultAndTheReplyComesWhenAdmitted() throws Exception {
        worker.on("work", TestWorker.deferForever());
        http.invoke("sync-lock-wait", "w-lock-1", "wait=0", request("sync-wait-holder", "B-W1"));

        var queued = http.invoke("sync-lock-wait", "w-lock-2", "wait=0", request("sync-wait-queued", "B-W1"));
        assertThat(queued.getResponse().getStatus()).isEqualTo(202);
        assertThat(process("sync-wait-queued").getStatus()).isNotIn(ProcessStatus.ERROR, ProcessStatus.COMPLETED);

        worker.on("work", TestWorker.succeed());
        complete("sync-wait-holder", "work");

        var answer = http.get(body(queued).path("location").asText(), "wait=5");
        assertThat(answer.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(answer).path("reply").path("bookingId").asText()).isEqualTo("B-W1");
    }
}
