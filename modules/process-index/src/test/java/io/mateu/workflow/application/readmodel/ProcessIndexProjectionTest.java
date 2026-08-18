package io.mateu.workflow.application.readmodel;

import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.infra.out.memory.InMemoryProcessIndexRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection is one function with two hosts (the engine's in-process handler and the standalone
 * projector), so what is worth pinning here is the rules themselves — what a status change becomes,
 * and which of two events wins — rather than either host's plumbing.
 */
class ProcessIndexProjectionTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 10, 0);

    private final InMemoryProcessIndexRepository store = new InMemoryProcessIndexRepository();

    @Test
    void projectsTheWholeShapeOfTheEventIncludingTheOwningShard() {
        ProcessIndexProjection.apply(store, event("p1", "order-4711", "RUNNING", 40, T0, "s2"));

        var row = store.findByProcessId("p1").orElseThrow();
        assertThat(row.businessKey()).isEqualTo("order-4711");
        assertThat(row.workflowDefinitionId()).isEqualTo("order-fulfilment");
        assertThat(row.status()).isEqualTo("RUNNING");
        assertThat(row.completionPercentage()).isEqualTo(40);
        // Stamped on the owning shard, not by whoever projected it — that is what lets one projector
        // consume every shard's events and still record where each process lives.
        assertThat(row.shardId()).isEqualTo("s2");
    }

    @Test
    void ordersByTheEventsEmitTimeSoALateArrivalCannotClobberANewerState() {
        ProcessIndexProjection.apply(store, event("p1", "order-4711", "COMPLETED", 100, T0.plusMinutes(5), "s2"));
        // The seed event of the same process, dispatched after the cascade that finished it.
        ProcessIndexProjection.apply(store, event("p1", "order-4711", "PENDING", 0, T0, "s2"));

        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void aRedeliveryOfTheSameEventChangesNothing() {
        var e = event("p1", "order-4711", "RUNNING", 40, T0, "s2");
        ProcessIndexProjection.apply(store, e);
        ProcessIndexProjection.apply(store, e);

        assertThat(store.countByStatus()).containsExactly(java.util.Map.entry("RUNNING", 1L));
    }

    @Test
    void indexesAcrossShardsAsOneFleet() {
        ProcessIndexProjection.apply(store, event("p1", "k1", "RUNNING", 10, T0, "s0"));
        ProcessIndexProjection.apply(store, event("p2", "k2", "RUNNING", 10, T0, "s1"));
        ProcessIndexProjection.apply(store, event("p3", "k3", "COMPLETED", 100, T0, "s1"));

        assertThat(store.findByStatusIn(java.util.List.of("RUNNING"))).hasSize(2);
        assertThat(store.countByStatus())
                .containsEntry("RUNNING", 2L)
                .containsEntry("COMPLETED", 1L);
        assertThat(store.findByBusinessKey("k3").orElseThrow().shardId()).isEqualTo("s1");
    }

    static ProcessStatusChanged event(String processId, String businessKey, String status,
                                      int completion, LocalDateTime occurredAt, String shardId) {
        return new ProcessStatusChanged(processId, businessKey, "order-fulfilment", 1, status,
                completion, T0, T0, "COMPLETED".equals(status) ? occurredAt : null, occurredAt, shardId);
    }
}
