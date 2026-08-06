package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.schema.ManagedSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's schema, from the point of view of an application that embeds the engine rather than
 * running the standalone orchestrator.
 *
 * <p>Until the migrations moved into this jar, that application got none of them: they shipped with
 * {@code apps/orchestrator-standalone-app}, so embedding the engine with {@code ddl-auto} meant
 * running on a schema of primary keys and nothing else — Hibernate's {@code update} path emits no
 * index DDL — and every deadline scan, outbox claim and message correlation became a sequential
 * scan. Nothing tested it, because nothing here ever booted the way an embedder boots: with a data
 * source and no {@code spring.flyway.*} configuration of its own.
 */
class WorkflowSchemaAutoConfigurationTest {

    private ApplicationContextRunner embedder(String database) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, WorkflowSchemaAutoConfiguration.class))
                // Deliberately no spring.flyway.* here: an embedder configures a data source and
                // expects the engine it added to be able to run on it.
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + database
                                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=");
    }

    @Test
    void anEmbedderGetsTheSchemaWithoutConfiguringAnything() {
        embedder("embedder-schema").run(context -> {
            assertThat(context).hasSingleBean(ManagedSchemaInitializer.class);

            var dataSource = context.getBean(DataSource.class);
            assertThat(tableExists(dataSource, "process_entity")).isTrue();
            assertThat(tableExists(dataSource, "step_execution_entity")).isTrue();
            assertThat(tableExists(dataSource, "outbox_message_entity")).isTrue();

            // The whole point of the migrations: the indexes ddl-auto never creates. Without
            // idx_step_exec_deadline the deadline scan — an index range over deadline_at, run on
            // every scheduler tick — is a full table scan.
            assertThat(indexExists(dataSource, "idx_step_exec_deadline")).isTrue();
            assertThat(indexExists(dataSource, "idx_outbox_status_ts")).isTrue();
        });
    }

    /**
     * The reason the engine keeps its own history table. A host application's migrations are
     * numbered from V1 as well; sharing one history means the engine's V1 and the host's V1 are the
     * same row, and whichever runs second fails validation on a checksum mismatch and the
     * application does not start.
     */
    @Test
    void theEngineRecordsItsHistoryAwayFromTheApplicationsOwn() {
        embedder("embedder-history").run(context -> {
            var dataSource = context.getBean(DataSource.class);

            assertThat(tableExists(dataSource, "eventconductor_schema_history")).isTrue();
            assertThat(tableExists(dataSource, "flyway_schema_history")).isFalse();
        });
    }

    /** Opting out has to mean the engine leaves the database alone, not that it half-applies. */
    @Test
    void optingOutLeavesTheDatabaseUntouched() {
        embedder("embedder-opted-out")
                .withPropertyValues("workflow.schema.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ManagedSchemaInitializer.class);

                    var dataSource = context.getBean(DataSource.class);
                    assertThat(tableExists(dataSource, "process_entity")).isFalse();
                    assertThat(tableExists(dataSource, "eventconductor_schema_history")).isFalse();
                });
    }

    /** A host that names the history table keeps its own convention. */
    @Test
    void theHistoryTableIsConfigurable() {
        embedder("embedder-named-history")
                .withPropertyValues("workflow.schema.table=my_engine_history")
                .run(context -> {
                    var dataSource = context.getBean(DataSource.class);
                    assertThat(tableExists(dataSource, "my_engine_history")).isTrue();
                    assertThat(tableExists(dataSource, "eventconductor_schema_history")).isFalse();
                });
    }

    private boolean tableExists(DataSource dataSource, String table) throws SQLException {
        return countOf(dataSource,
                "select count(*) from information_schema.tables where upper(table_name) = ?", table);
    }

    private boolean indexExists(DataSource dataSource, String index) throws SQLException {
        return countOf(dataSource,
                "select count(*) from information_schema.indexes where upper(index_name) = ?", index);
    }

    private boolean countOf(DataSource dataSource, String sql, String name) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, name.toUpperCase());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }
}
