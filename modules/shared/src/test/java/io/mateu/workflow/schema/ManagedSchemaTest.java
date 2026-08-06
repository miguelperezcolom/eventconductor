package io.mateu.workflow.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner each engine applies its own schema with.
 *
 * <p>Exercised here against a location of its own rather than a real engine's, because what these
 * tests are about is the arrangement — where the history goes, what happens on a schema somebody
 * else built, what a second run does — and not the contents of any one engine's migrations.
 */
class ManagedSchemaTest {

    private static final String LOCATION = "classpath:db/migration/managed-schema-test";

    private DataSource database(String name) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }

    private ManagedSchema schema(String historyTable) {
        return new ManagedSchema("probe", LOCATION, historyTable);
    }

    @Test
    void appliesTheMigrationsIncludingTheIndexesDdlAutoWouldNotCreate() throws Exception {
        var dataSource = database("managed-schema-apply");

        schema("probe_history").migrate(dataSource);

        assertThat(exists(dataSource, "tables", "managed_schema_probe")).isTrue();
        assertThat(exists(dataSource, "indexes", "idx_managed_schema_probe")).isTrue();
    }

    @Test
    void recordsItsHistoryInItsOwnTableAndLeavesFlywaysDefaultAlone() throws Exception {
        var dataSource = database("managed-schema-history");

        schema("probe_history").migrate(dataSource);

        assertThat(exists(dataSource, "tables", "probe_history")).isTrue();
        // The host application's own migrations live in flyway_schema_history and are numbered from
        // V1 too. Writing there would put two independent numbering spaces in one table, and the
        // second one to run would fail validation on a checksum mismatch.
        assertThat(exists(dataSource, "tables", "flyway_schema_history")).isFalse();
    }

    /**
     * Two engines pointed at one database — the arrangement the chart deploys — must not see each
     * other's history at all, even though both start at V1.
     */
    @Test
    void twoEnginesShareADatabaseWithoutSharingAHistory() throws Exception {
        var dataSource = database("managed-schema-two-engines");

        schema("probe_history_a").migrate(dataSource);
        schema("probe_history_b").migrate(dataSource);

        assertThat(exists(dataSource, "tables", "probe_history_a")).isTrue();
        assertThat(exists(dataSource, "tables", "probe_history_b")).isTrue();
    }

    /**
     * Baselining at 0 rather than at 1 is what makes adoption over an existing schema work: Flyway
     * would otherwise call a non-empty schema "already up to date", skip V1, and then run V2
     * against tables it never created.
     */
    @Test
    void appliesV1OverASchemaSomethingElseAlreadyBuilt() throws Exception {
        var dataSource = database("managed-schema-adopted");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated_table (id VARCHAR(64) NOT NULL PRIMARY KEY)");
        }

        schema("probe_history").migrate(dataSource);

        assertThat(exists(dataSource, "tables", "managed_schema_probe")).isTrue();
        assertThat(exists(dataSource, "indexes", "idx_managed_schema_probe")).isTrue();
    }

    @Test
    void runningTwiceIsANoOp() throws Exception {
        var dataSource = database("managed-schema-twice");

        schema("probe_history").migrate(dataSource);
        schema("probe_history").migrate(dataSource);

        assertThat(exists(dataSource, "tables", "managed_schema_probe")).isTrue();
    }

    @Test
    void theInitializerMigratesWhenTheContextRefreshes() throws Exception {
        var dataSource = database("managed-schema-initializer");

        new ManagedSchemaInitializer(schema("probe_history"), dataSource).afterPropertiesSet();

        assertThat(exists(dataSource, "tables", "managed_schema_probe")).isTrue();
    }

    private boolean exists(DataSource dataSource, String catalog, String name) throws SQLException {
        var sql = "select count(*) from information_schema." + catalog
                + " where upper(" + (catalog.equals("tables") ? "table_name" : "index_name") + ") = ?";
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
