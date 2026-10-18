package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The incidents query on a migrated database: the failed steps of failed processes only, newest first,
 * each with its latest error line, and grouped into incidents by {@link IncidentService} the same way
 * whatever the database.
 */
abstract class IncidentQueryContract {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0);

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    JpaIncidentRepository incidents;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void openContext() {
        dataSource = newMigratedDatabase();
        context = AnalyticsJpaTestContext.open(dataSource);
        jdbc = context.getBean(JdbcTemplate.class);
        incidents = new JpaIncidentRepository(context.getBean(StepExecutionEntityRepository.class),
                context.getBean(LogMessageEntityRepository.class));

        process("p1", "orders", "ERROR");
        step("s1", "p1", "orders", "charge", "ERROR", 1);
        log("p1", "s1", "Error", "Card 1 declined", 0);
        log("p1", "s1", "error", "Card 11 declined", 1);
        log("p1", "s1", "Info", "operator note", 2);
        step("s1b", "p1", "orders", "start", "COMPLETED", 0);

        process("p2", "orders", "ERROR");
        step("s2", "p2", "orders", "charge", "TIMEOUT", 3);
        log("p2", "s2", "Error", "Card 2 declined", 3);

        process("p3", "orders", "ERROR");
        step("s3", "p3", "orders", "ship", "ERROR", 2);

        process("p4", "orders", "COMPENSATED");
        step("s4", "p4", "orders", "charge", "ERROR", 4);
        process("p5", "refunds", "ERROR");
        step("s5", "p5", "refunds", "charge", "ERROR", 5);
        log("p5", "s5", "Error", "Card 5 declined", 5);
        process("p6", "orders", "RUNNING");
        step("s6", "p6", "orders", "charge", "ERROR", 6);
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

    @Test
    void readsTheFailedStepsOfFailedProcessesNewestFirstWithTheirLatestError() {
        var found = incidents.findFailedSteps(null, 100);

        assertThat(found).extracting(row -> row.stepExecutionId()).containsExactly("s5", "s2", "s3", "s1");
        var s1 = found.getLast();
        assertThat(s1.error()).isEqualTo("Card 11 declined");
        assertThat(s1.businessKey()).isEqualTo("bk-p1");
        assertThat(s1.workflowDefinitionId()).isEqualTo("orders");
        assertThat(s1.failedAt()).isEqualTo(T0.plusMinutes(1));
        assertThat(found.get(1).status()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(found.get(2).error()).isNull();
    }

    @Test
    void filtersByDefinitionAndLimits() {
        assertThat(incidents.findFailedSteps("refunds", 100)).extracting(row -> row.processId()).containsExactly("p5");
        assertThat(incidents.findFailedSteps(null, 2)).hasSize(2);
        assertThat(incidents.findFailedSteps(null, 0)).isEmpty();
    }

    @Test
    void groupsIntoIncidents() {
        var service = new IncidentService(incidents, null, null, null, 1000, 10, 10);
        var groups = service.incidents("orders").groups();

        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().stepId()).isEqualTo("charge");
        assertThat(groups.getFirst().errorFamily()).isEqualTo("Card # declined");
        assertThat(groups.getFirst().failedSteps()).isEqualTo(2);
        assertThat(groups.getFirst().processes()).isEqualTo(2);
        assertThat(groups.get(1).errorFamily()).isEqualTo(IncidentService.NO_MESSAGE);
    }

    private void process(String id, String definition, String status) {
        jdbc.update("INSERT INTO process_entity (id, business_key, name, status, workflow_definition_id, created) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, "bk-" + id, definition, status, definition, Timestamp.valueOf(T0));
    }

    private void step(String id, String processId, String definition, String stepId, String status, int minute) {
        jdbc.update("INSERT INTO step_execution_entity (id, process_id, workflow_definition_id, step_id, status, _order, "
                        + "finished_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, processId, definition, stepId, status, minute,
                Timestamp.valueOf(T0.plusMinutes(minute)));
    }

    private void log(String processId, String stepExecutionId, String type, String message, int minute) {
        jdbc.update("INSERT INTO log_message_entity (id, timestamp, process_id, step_execution_id, message_type, message) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", java.util.UUID.randomUUID().toString(),
                Timestamp.valueOf(T0.plusMinutes(minute)), processId, stepExecutionId, type, message);
    }
}
