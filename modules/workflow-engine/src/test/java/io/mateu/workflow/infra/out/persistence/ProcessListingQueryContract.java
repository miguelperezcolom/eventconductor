package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process listing's filtered query on a migrated database. The definition and status filters
 * used to read {@code (:ids is null or … in :ids)}, which renders as {@code (?, ?) is null} with two
 * or more ids — a tag matching several definitions — and is not portable SQL. They now take a flag.
 */
abstract class ProcessListingQueryContract {

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ProcessEntityRepository processes;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void openContext() {
        dataSource = newMigratedDatabase();
        context = AnalyticsJpaTestContext.open(dataSource);
        jdbc = context.getBean(JdbcTemplate.class);
        processes = context.getBean(ProcessEntityRepository.class);
        var created = LocalDateTime.of(2026, 1, 10, 9, 0);
        insert("p1", "order", "COMPLETED", created);
        insert("p2", "refund", "ERROR", created.plusMinutes(1));
        insert("p3", "invoice", "COMPLETED", created.plusMinutes(2));
        insert("p4", "order", "RUNNING", created.plusMinutes(3));
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
        if (dataSource != null) {
            dispose(dataSource);
        }
    }

    private void insert(String id, String definition, String status, LocalDateTime created) {
        jdbc.update("INSERT INTO process_entity (id, business_key, name, status, workflow_definition_id, created) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, "bk-" + id, "name-" + id, status, definition, Timestamp.valueOf(created));
    }

    private List<String> search(boolean anyDefinition, List<String> definitionIds, boolean anyStatus, List<String> statuses) {
        return processes.searchSummaries(false, null, null, null, anyDefinition, definitionIds, anyStatus, statuses,
                        null, null, PageRequest.of(0, 50)).stream()
                .map(ProcessEntityRepository.ProcessSummaryView::getId).sorted().toList();
    }

    @Test
    void severalDefinitionsAndSeveralStatusesFilterTogether() {
        assertThat(search(false, List.of("order", "refund"), false, List.of("COMPLETED", "ERROR")))
                .containsExactly("p1", "p2");
        assertThat(processes.countSummaries(false, null, null, null, false, List.of("order", "refund"),
                false, List.of("COMPLETED", "ERROR"), null, null)).isEqualTo(2);
    }

    @Test
    void theFlagsSwitchTheFiltersOff() {
        assertThat(search(true, List.of(""), true, List.of(""))).containsExactly("p1", "p2", "p3", "p4");
        assertThat(search(false, List.of("order", "invoice"), true, List.of(""))).containsExactly("p1", "p3", "p4");
        assertThat(search(true, List.of(""), false, List.of("COMPLETED"))).containsExactly("p1", "p3");
    }
}
