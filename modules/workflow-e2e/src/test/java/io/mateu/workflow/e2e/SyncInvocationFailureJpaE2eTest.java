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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure contract on the durable path (embedded + JPA on H2): the failure answer is written
 * with the transition, through the relay; a refusal for a busy lock rolls back the invocation, the
 * process and the lock row together.
 */
class SyncInvocationFailureJpaE2eTest extends AbstractJpaE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired SyncInvocationService service;
    @Autowired SyncReplyWaiters waiters;
    @Autowired WorkflowMetrics metrics;
    @Autowired InvocationEntityRepository invocations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSagaThatFailsAnswers502WithTheRollbackUnderWay() throws Exception {
        worker.on("book", TestWorker.succeed());
        worker.on("pay", TestWorker.fail());
        worker.on("undo-book", TestWorker.deferForever());
        var http = new SyncInvocationHttp(service, waiters, metrics);

        var result = http.invoke("sync-saga", "jf-1", "wait=10",
                "{\"businessKey\": \"sync-jpa-fail-1\", \"variables\": {\"bookingId\": \"B-JF1\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        var body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(body.path("compensation").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aRefusalForABusyLockLeavesNothingBehind() throws Exception {
        worker.on("work", TestWorker.deferForever());
        var http = new SyncInvocationHttp(service, waiters, metrics);
        http.invoke("sync-lock-fail", "jl-1", "wait=0",
                "{\"businessKey\": \"sync-jpa-lock-1\", \"variables\": {\"bookingId\": \"B-JL\"}}");
        awaitStatus("sync-jpa-lock-1", io.mateu.workflow.domain.aggregates.ProcessStatus.RUNNING);

        var refused = http.invoke("sync-lock-fail", "jl-2", "wait=0",
                "{\"businessKey\": \"sync-jpa-lock-2\", \"variables\": {\"bookingId\": \"B-JL\"}}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(processOpt("sync-jpa-lock-2")).isEmpty();
        assertThat(invocations.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from process_lock", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from process_lock_waiter", Integer.class)).isZero();
    }
}
