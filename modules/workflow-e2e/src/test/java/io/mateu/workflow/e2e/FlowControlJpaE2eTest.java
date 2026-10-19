package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flow control on embedded + JPA (H2): trigger starts debounced and throttled through flow_control_key /
 * flow_control_pending and released by the FlowControlPass; a rate-limited topic's tasks deferred in
 * deferred_dispatch and dispatched by the RateLimitedDispatchPass — with their timeout not running meanwhile.
 */
@TestPropertySource(properties = {
        "workflow.flow-control.release-interval=PT0.2S",
        "workflow.workers.rate-limits.limited.permits=1",
        "workflow.workers.rate-limits.limited.period=PT1S",
        "workflow.workers.rate-limit-interval=PT0.1S"})
class FlowControlJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired InboundEventService inbound;
    @Autowired JdbcTemplate jdbc;

    private InboundEventService.Result send(String destination, String type, String data) {
        return inbound.receive(new InboundEvent(destination, UUID.randomUUID().toString(), "e2e", type, null, null, null,
                "application/json", data, Map.of()));
    }

    @Test
    void aDebouncedTriggerStartsOnceWithTheLastEventThroughTheDatabase() {
        worker.on("reindex", TestWorker.succeed());
        for (int rev = 1; rev <= 3; rev++) {
            var result = send("e2e-catalog", "com.acme.product.edited", "{\"sku\":\"S1\",\"rev\":" + rev + "}");
            assertThat(result.receipts()).singleElement().satisfies(r -> assertThat(r.outcome()).isEqualTo(EventReceipt.Outcome.DEBOUNCED));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flow_control_pending WHERE kind = 'DEBOUNCE'", Integer.class))
                .isEqualTo(1);
        awaitStatus("reindex-S1-3", ProcessStatus.COMPLETED);
        assertThat(processOpt("reindex-S1-1")).isEmpty();
        assertThat(processOpt("reindex-S1-2")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flow_control_pending", Integer.class)).isZero();
    }

    @Test
    void aThrottledTriggerQueuesInTheDatabaseAndReleasesWindowByWindow() {
        worker.on("payout", TestWorker.succeed());
        var outcomes = new java.util.ArrayList<EventReceipt.Outcome>();
        for (int n = 1; n <= 3; n++) {
            outcomes.add(send("e2e-payouts", "com.acme.payout.requested", "{\"n\":" + n + "}").receipts().getFirst().outcome());
        }
        assertThat(outcomes).containsExactly(EventReceipt.Outcome.STARTED, EventReceipt.Outcome.THROTTLED,
                EventReceipt.Outcome.THROTTLED);
        awaitStatus("payout-1", ProcessStatus.COMPLETED);
        assertThat(processOpt("payout-3")).isEmpty();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(processOpt("payout-2").map(p -> p.getStatus())).contains(ProcessStatus.COMPLETED);
            assertThat(processOpt("payout-3").map(p -> p.getStatus())).contains(ProcessStatus.COMPLETED);
        });
        var created = List.of(process("payout-2").getCreated(), process("payout-3").getCreated());
        assertThat(Duration.between(created.get(0), created.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void aRateLimitedTopicDispatchesOneTaskPerWindowWithoutTimingTheOthersOut() {
        var at = new CopyOnWriteArrayList<Instant>();
        worker.on("limited-call", (request, callback, n) -> {
            at.add(Instant.now());
            callback.complete();
        });
        createProcess("rate-limited-call", "rl-1");
        createProcess("rate-limited-call", "rl-2");
        createProcess("rate-limited-call", "rl-3");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (var key : List.of("rl-1", "rl-2", "rl-3")) {
                assertThat(processOpt(key).map(p -> p.getStatus())).contains(ProcessStatus.COMPLETED);
            }
        });
        // One per second, and none of them timed out (a 500 ms timeout) while it waited for its permit.
        var sorted = at.stream().sorted().toList();
        assertThat(sorted).hasSize(3);
        assertThat(Duration.between(sorted.get(0), sorted.get(2))).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deferred_dispatch", Integer.class)).isZero();
    }
}
