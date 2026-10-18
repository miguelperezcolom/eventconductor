package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The heap index (memory persistence): the same last-newest-wins contract as the database stores. */
class InMemoryProcessIndexRepositoryTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 10, 0);

    final InMemoryProcessIndexRepository store = new InMemoryProcessIndexRepository();

    private static ProcessIndexRow row(String id, String key, String definition, String status, LocalDateTime at) {
        return new ProcessIndexRow(id, key, "n", definition, 1, status, 0, T0, null, null, at, null);
    }

    @Test
    void forgetsPurgedProcesses() {
        store.upsert(row("p1", "k1", "orders", "COMPLETED", T0));
        store.upsert(row("p2", "k2", "orders", "RUNNING", T0));

        assertThat(store.deleteByProcessIds(List.of("p1", "gone"))).isEqualTo(1);
        assertThat(store.findByProcessId("p1")).isEmpty();
        assertThat(store.findByProcessId("p2")).isPresent();
    }

    @Test
    void keepsTheNewestStateWhateverOrderEventsArriveIn() {
        store.upsert(row("p1", "k1", "orders", "COMPLETED", T0.plusMinutes(1)));
        store.upsert(row("p1", "k1", "orders", "RUNNING", T0));   // late
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");

        // Without a stamp on either side the incoming row wins.
        store.upsert(row("p1", "k1", "orders", "ERROR", null));
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("ERROR");
        store.upsert(row("p1", "k1", "orders", "RUNNING", T0));
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("RUNNING");
    }

    @Test
    void answersLookupsAndCounts() {
        store.upsert(row("p1", "k1", "orders", "RUNNING", T0));
        store.upsert(row("p2", "k2", "orders", "ERROR", T0));
        store.upsert(row("p3", "k3", "refunds", "RUNNING", T0));

        assertThat(store.findByStatusIn(List.of("RUNNING"))).extracting(ProcessIndexRow::processId)
                .containsExactlyInAnyOrder("p1", "p3");
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("orders", List.of("RUNNING")))
                .extracting(ProcessIndexRow::processId).containsExactly("p1");
        assertThat(store.findByBusinessKey("k2").orElseThrow().processId()).isEqualTo("p2");
        assertThat(store.findByBusinessKey("none")).isEmpty();
        assertThat(store.findByProcessId("none")).isEmpty();
        assertThat(store.countByStatus()).isEqualTo(Map.of("RUNNING", 2L, "ERROR", 1L));
    }

    @Test
    void doesNotPageTheListingSoTheCallerFallsBack() {
        // "Not supported" rather than an empty page that would read as "no processes".
        store.upsert(row("p1", "k1", "orders", "RUNNING", T0));
        assertThat(store.search(null, false, 0, 10)).isEmpty();
    }
}
