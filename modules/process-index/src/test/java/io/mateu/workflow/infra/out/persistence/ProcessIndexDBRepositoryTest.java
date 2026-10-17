package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jpa adapter — the index in the engine's own persistence unit (embedded, jpa). It owes the same
 * contract as the JDBC store: one row per process, a late event never clobbers a newer state, and the
 * operator listing filtered, ordered and paged in the database.
 */
@DataJpaTest(properties = "workflow.persistence=jpa")
@Import(ProcessIndexDBRepository.class)
class ProcessIndexDBRepositoryTest {

    @SpringBootConfiguration
    @org.springframework.boot.autoconfigure.AutoConfigurationPackage
    static class Config {
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 10, 0);

    @Autowired
    ProcessIndexDBRepository store;

    private static ProcessIndexRow row(String id, String key, String name, String definition, String status,
                                       LocalDateTime created, LocalDateTime updatedAt) {
        return new ProcessIndexRow(id, key, name, definition, 3, status, 25, created, created, null, updatedAt, "s1");
    }

    @Test
    void roundTripsTheWholeRow() {
        var row = new ProcessIndexRow("p1", "order-1", "Order 1", "orders", 3, "COMPLETED", 100, T0,
                T0.plusSeconds(1), T0.plusMinutes(2), T0.plusMinutes(2), "s7");
        store.upsert(row);
        assertThat(store.findByProcessId("p1")).contains(row);
        assertThat(store.findByBusinessKey("order-1")).contains(row);
        assertThat(store.findByProcessId("nope")).isEmpty();
    }

    @Test
    void aLaterEventUpdatesTheRowAndALateOlderOneIsIgnored() {
        store.upsert(row("p1", "order-1", "Order", "orders", "RUNNING", T0, T0));
        store.upsert(row("p1", "order-1", "Order", "orders", "COMPLETED", T0, T0.plusMinutes(1)));
        store.upsert(row("p1", "order-1", "Order", "orders", "RUNNING", T0, T0));   // a late redelivery

        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(store.countByStatus()).isEqualTo(Map.of("COMPLETED", 1L));
    }

    @Test
    void anEventWithoutAStampIsApplied() {
        store.upsert(row("p1", "order-1", "Order", "orders", "RUNNING", T0, T0.plusMinutes(5)));
        store.upsert(row("p1", "order-1", "Order", "orders", "ERROR", T0, null));
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("ERROR");
        // And over a row without a stamp, any event is applied.
        store.upsert(row("p1", "order-1", "Order", "orders", "RUNNING", T0, T0));
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("RUNNING");
    }

    @Test
    void answersByStatusAndByDefinitionAndCountsPerStatus() {
        store.upsert(row("p1", "k1", "A", "orders", "RUNNING", T0, T0));
        store.upsert(row("p2", "k2", "B", "orders", "ERROR", T0, T0));
        store.upsert(row("p3", "k3", "C", "refunds", "RUNNING", T0, T0));

        assertThat(store.findByStatusIn(List.of("RUNNING"))).extracting(ProcessIndexRow::processId)
                .containsExactlyInAnyOrder("p1", "p3");
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("orders", List.of("RUNNING", "ERROR")))
                .extracting(ProcessIndexRow::processId).containsExactlyInAnyOrder("p1", "p2");
        assertThat(store.countByStatus()).isEqualTo(Map.of("RUNNING", 2L, "ERROR", 1L));
    }

    @Test
    void pagesTheListingNewestFirstAndFiltersByTextAndErrors() {
        for (var i = 0; i < 5; i++) {
            store.upsert(row("p" + i, "order-" + i, i == 1 ? "Refund" : "Order", "orders",
                    i == 3 ? "ERROR" : "RUNNING", T0.plusMinutes(i), T0));
        }
        store.upsert(row("old", null, null, "orders", "RUNNING", null, T0));   // no name, key or creation

        var first = store.search(null, false, 0, 2).orElseThrow();
        assertThat(first.totalElements()).isEqualTo(6);
        assertThat(first.content()).extracting(ProcessIndexRow::processId).containsExactly("p4", "p3");
        assertThat(first.pageNumber()).isZero();
        assertThat(first.pageSize()).isEqualTo(2);

        // Rows without a creation time sort last.
        var last = store.search(" ", false, 2, 2).orElseThrow();
        assertThat(last.content()).extracting(ProcessIndexRow::processId).containsExactly("p0", "old");

        // Past the end: the last real page.
        assertThat(store.search(null, false, 9, 2).orElseThrow().pageNumber()).isEqualTo(2);

        // Text matches the name or the business key, case-insensitively.
        assertThat(store.search("REFUND", false, 0, 10).orElseThrow().content())
                .extracting(ProcessIndexRow::processId).containsExactly("p1");
        assertThat(store.search("order-2", false, 0, 10).orElseThrow().content())
                .extracting(ProcessIndexRow::processId).containsExactly("p2");
        var errors = store.search(null, true, 0, 10).orElseThrow();
        assertThat(errors.totalElements()).isEqualTo(1);
        assertThat(errors.content()).extracting(ProcessIndexRow::processId).containsExactly("p3");
    }
}
