package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.SyncInvocationHttp;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.out.persistence.InvocationEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synchronous invocation on the durable path (embedded + JPA on H2): the process runs through the
 * outbox relay on another thread, and the caller is woken when its REPLY commits. Concurrent
 * requests with one key create exactly one process.
 */
class SyncInvocationJpaE2eTest extends AbstractJpaE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired SyncInvocationService service;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;
    @Autowired InvocationEntityRepository invocations;

    @Test
    void theReplyReachesTheCallerThroughTheRelay() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-J1")));
        var http = new SyncInvocationHttp(service, waiters, metrics);

        var result = http.invoke("sync-reply-end", "jk-1", "wait=10",
                "{\"businessKey\": \"sync-jpa-1\", \"variables\": {\"bookingId\": \"B-J1\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("reply").path("confirmation").asText()).isEqualTo("C-J1");
        assertThat(invocations.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyCreateOneProcess() throws Exception {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-J2")));
        var http = new SyncInvocationHttp(service, waiters, metrics);
        var request = "{\"businessKey\": \"sync-jpa-2\", \"variables\": {\"bookingId\": \"B-J2\"}}";

        var pool = Executors.newFixedThreadPool(6);
        var calls = new ArrayList<Callable<String>>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> http.invoke("sync-reply-end", "jk-2", "wait=10", request).getResponse().getContentAsString());
        }
        var processIds = new java.util.HashSet<String>();
        for (var future : pool.invokeAll(calls)) {
            processIds.add(JSON.readTree(future.get()).path("processId").asText());
        }
        pool.shutdown();

        assertThat(processIds).hasSize(1);
        assertThat(invocations.count()).isEqualTo(1);
        assertThat(processRepository.findAll()).hasSize(1);
        assertThat(worker.invocationsOf("book")).isEqualTo(1);
    }
}
