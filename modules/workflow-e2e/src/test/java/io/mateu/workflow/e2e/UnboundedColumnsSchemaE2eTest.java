package io.mateu.workflow.e2e;

import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's columns that hold as much as their author needed, asserted on the schema Hibernate
 * builds from the mappings.
 *
 * <p>None of these fields declared a length, so Hibernate mapped every one of them to
 * {@code varchar(255)} — narrower than the migrations intended in every case, and narrower than the
 * content in several. A deployment that lets Hibernate build its own schema (the demo and the sagas
 * PoC both do, with the migrations off) therefore had a step's variables capped at 255 characters,
 * a log message truncated at 255, and a URL that could not exceed 255. It surfaced first in the
 * forms engine, where a form execution carrying a saga's variables could not be inserted at all.
 *
 * <p><b>The schema rather than a long value, deliberately.</b> Saving several kilobytes and reading
 * them back proves nothing here: H2 does not enforce VARCHAR length, so that test is green against
 * a 255-character column holding 2 KB — verified by reverting a mapping and watching the round trip
 * pass. The declared width is what actually differs, and H2 reports it faithfully even though it
 * will not police it.
 *
 * <p>The columns that were already right are asserted too, so they stay that way.
 */
class UnboundedColumnsSchemaE2eTest extends AbstractJpaE2eTest {

    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0}.{1} is not capped")
    @CsvSource({
            // Were varchar(255) against migrations that said 1024 or 2048.
            "step_entity,                 variables",
            "step_entity,                 precondition",
            "step_entity,                 description",
            "log_message_entity,          message",
            "resource_entity,             url",
            // Already TEXT in the mapping; asserted so they stay TEXT.
            "process_entity,              variables",
            "process_entity,              log",
            "process_entity,              workflow_definition_json",
            "step_execution_entity,       step_json",
            "step_execution_entity,       variables",
            "outbox_message_entity,       payload",
            "workflow_definition_entity,  steps_json",
    })
    void columnIsWideEnoughForWhateverItIsGiven(String table, String column) {
        var width = jdbc.queryForObject("""
                select character_maximum_length
                from information_schema.columns
                where lower(table_name) = lower(?) and lower(column_name) = lower(?)
                """, Long.class, table.trim(), column.trim());

        assertThat(width)
                .as("no column %s.%s in the generated schema", table, column)
                .isNotNull();
        assertThat(width)
                .as("%s.%s must not be capped: it holds JSON or free text, and in PostgreSQL a "
                        + "length is a constraint rather than an optimisation", table, column)
                .isGreaterThan(255L);
    }
}
