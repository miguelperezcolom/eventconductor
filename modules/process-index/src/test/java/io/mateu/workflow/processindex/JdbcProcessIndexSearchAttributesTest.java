package io.mateu.workflow.processindex;

import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.schema.ManagedSchema;
import io.mateu.workflow.search.SearchAttributeFilter;
import io.mateu.workflow.search.SearchAttributeFilter.Op;
import io.mateu.workflow.search.SearchAttributeType;
import io.mateu.workflow.search.SearchAttributeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search attributes in the remote read store (H2 through the shipped migration): projected with the row,
 * replaced by a newer event, kept by an event that says nothing about them, ignored from a stale one,
 * searched by every condition, and forgotten with the process.
 */
class JdbcProcessIndexSearchAttributesTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 18, 9, 0);

    JdbcProcessIndexStore store;

    @BeforeEach
    void freshDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:index-attrs-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(dataSource);
        store = new JdbcProcessIndexStore(dataSource);
    }

    static SearchAttributeValue v(String name, SearchAttributeType type, String raw) {
        return SearchAttributeValue.of(name, type, raw).orElseThrow();
    }

    static ProcessIndexRow row(String id, String status, LocalDateTime updatedAt, List<SearchAttributeValue> attributes) {
        return new ProcessIndexRow(id, "bk-" + id, "Order", "orders", 1, status, 10, T0.plusMinutes(id.hashCode() % 7),
                T0, null, updatedAt, null, attributes);
    }

    static List<String> ids(java.util.Optional<io.mateu.workflow.application.out.ProcessIndexRepository.ProcessIndexPage> page) {
        return page.orElseThrow().content().stream().map(ProcessIndexRow::processId).sorted().toList();
    }

    @Test
    void attributesAreProjectedReplacedKeptAndSearched() {
        store.upsert(row("p1", "RUNNING", T0, List.of(v("customerId", SearchAttributeType.KEYWORD, "C-9"),
                v("amount", SearchAttributeType.NUMBER, "150"), v("placedOn", SearchAttributeType.DATE, "2026-10-01"))));
        store.upsert(row("p2", "RUNNING", T0, List.of(v("customerId", SearchAttributeType.KEYWORD, "C-7"),
                v("amount", SearchAttributeType.NUMBER, "50"), v("note", SearchAttributeType.TEXT, "Rush order"))));
        store.upsert(row("p3", "ERROR", T0, null));

        assertThat(ids(store.search(null, false, List.of(new SearchAttributeFilter("customerId", Op.EQ, "C-9")), 0, 10)))
                .containsExactly("p1");
        assertThat(ids(store.search(null, false, List.of(new SearchAttributeFilter("amount", Op.GTE, "50")), 0, 10)))
                .containsExactly("p1", "p2");
        assertThat(ids(store.search(null, false, List.of(new SearchAttributeFilter("amount", Op.GT, "50"),
                new SearchAttributeFilter("placedOn", Op.LTE, "2026-10-01")), 0, 10))).containsExactly("p1");
        assertThat(ids(store.search(null, false, List.of(new SearchAttributeFilter("note", Op.EQ, "RUSH")), 0, 10)))
                .containsExactly("p2");
        assertThat(ids(store.search("bk-p2", false, List.of(new SearchAttributeFilter("amount", Op.LT, "100")), 0, 10)))
                .containsExactly("p2");
        assertThat(ids(store.search(null, true, List.of(new SearchAttributeFilter("amount", Op.LT, "100")), 0, 10))).isEmpty();
        assertThat(store.search(null, false, List.of(new SearchAttributeFilter("customerId", Op.EQ, "C-9")), 0, 10)
                .orElseThrow().totalElements()).isEqualTo(1);

        // A newer event replaces them; one that says nothing (null) keeps them; a stale one is ignored.
        store.upsert(row("p1", "RUNNING", T0.plusMinutes(1), List.of(v("customerId", SearchAttributeType.KEYWORD, "C-8"))));
        assertThat(store.searchAttributesOf("p1")).extracting(SearchAttributeValue::keyword).containsExactly("C-8");
        store.upsert(row("p1", "COMPLETED", T0.plusMinutes(2), null));
        assertThat(store.searchAttributesOf("p1")).extracting(SearchAttributeValue::keyword).containsExactly("C-8");
        store.upsert(row("p1", "RUNNING", T0, List.of(v("customerId", SearchAttributeType.KEYWORD, "STALE"))));
        assertThat(store.searchAttributesOf("p1")).extracting(SearchAttributeValue::keyword).containsExactly("C-8");
        store.upsert(row("p1", "COMPLETED", T0.plusMinutes(3), List.of()));
        assertThat(store.searchAttributesOf("p1")).isEmpty();

        assertThat(store.deleteByProcessIds(List.of("p2"))).isEqualTo(1);
        assertThat(store.searchAttributesOf("p2")).isEmpty();
        // No condition: the plain listing.
        assertThat(ids(store.search(null, false, List.of(), 0, 10))).containsExactly("p1", "p3");
    }
}
