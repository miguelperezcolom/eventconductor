package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.SyncInvocationHttp;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invocation as a Server-Sent Events stream: the caller watches the process move — status,
 * steps, log — and receives the reply as the last event, or a timeout event when the deadline
 * passes first; a reconnect resumes from the last event it saw.
 */
class SyncInvocationStreamE2eTest extends AbstractE2eTest {

    private static final Pattern EVENT = Pattern.compile("(?m)^event:(\\S+)");
    private static final Pattern ID = Pattern.compile("(?m)^id:(\\S+)");

    @Autowired SyncInvocationService service;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;

    SyncInvocationHttp http;

    @BeforeEach
    void http() {
        http = new SyncInvocationHttp(service, waiters, metrics);
    }

    private static List<String> names(String sse) {
        var names = new ArrayList<String>();
        var matcher = EVENT.matcher(sse);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    private static List<String> ids(String sse) {
        var ids = new ArrayList<String>();
        var matcher = ID.matcher(sse);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    @Test
    void theStreamShowsTheProcessMovingAndEndsWithTheReply() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-S1")));

        var sse = http.invokeStreaming("sync-reply-end", "sk-1", "wait=5",
                "{\"businessKey\": \"sync-stream-1\", \"variables\": {\"bookingId\": \"B-S1\"}}", false);

        var names = names(sse);
        assertThat(names).first().isEqualTo("status");
        assertThat(names).contains("step", "log");
        assertThat(names).last().isEqualTo("reply");
        assertThat(sse).contains("\"stepId\":\"book\"").contains("\"confirmation\":\"C-S1\"");
        assertThat(ids(sse)).isSorted();
    }

    @Test
    void aDeadlineThatPassesFirstEndsTheStreamWithATimeoutEvent() throws Exception {
        worker.on("book", TestWorker.deferForever());

        var sse = http.invokeStreaming("sync-reply-end", "sk-2", "wait=1",
                "{\"businessKey\": \"sync-stream-2\", \"variables\": {\"bookingId\": \"B-S2\"}}", false);

        assertThat(names(sse)).last().isEqualTo("timeout");
        assertThat(names(sse)).doesNotContain("reply");
        assertThat(sse).contains("/workflow/api/invocations/");
    }

    @Test
    void followingKeepsTheStreamOpenPastAnEarlyReplyUntilTheProcessEnds() throws Exception {
        worker.on("notify", TestWorker.succeed());

        var sse = http.invokeStreaming("sync-reply-early", "sk-3", "wait=5",
                "{\"businessKey\": \"sync-stream-3\", \"variables\": {\"bookingId\": \"B-S3\"}}", true);

        var names = names(sse);
        assertThat(names).contains("reply");
        assertThat(sse).contains("\"processStatus\":\"COMPLETED\"");
    }

    @Test
    void aReconnectResumesAfterTheLastEventSeen() throws Exception {
        worker.on("book", TestWorker.deferForever());
        var first = http.invokeStreaming("sync-reply-end", "sk-4", "wait=1",
                "{\"businessKey\": \"sync-stream-4\", \"variables\": {\"bookingId\": \"B-S4\"}}", false);
        var lastId = ids(first).getLast();
        var location = Pattern.compile("/workflow/api/invocations/[\\w-]+").matcher(first);
        assertThat(location.find()).isTrue();

        Thread.sleep(5);
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(step("sync-stream-4", "book").id(),
                List.of(), "", StepExecutionStatus.COMPLETED));
        var resumed = http.getStreaming(location.group(), "wait=5", lastId);

        assertThat(names(resumed)).last().isEqualTo("reply");
        // At-least-once: what the first stream saw may come again, but always under the same key,
        // and what it did not see — the book step completing — is there.
        assertThat(resumed).contains("\"key\":\"step:" + step("sync-stream-4", "book").id() + ":COMPLETED\"");
        assertThat(ids(resumed).getFirst()).isGreaterThan(ids(first).getFirst().substring(0, 13));
    }
}
