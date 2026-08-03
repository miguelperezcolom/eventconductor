package io.mateu.workflow;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Adopting Flyway over a schema {@code ddl-auto} already built — the upgrade the deployment guide
 * recommends, and the one every existing deployment needs, because Flyway used to be off by
 * default and the indexes only come from the migrations.
 *
 * <p>The claim it rests on is that "every later migration is written with IF NOT EXISTS". It has
 * to be: Flyway baselines such a schema at V1 and then runs V2 onwards over a table set that was
 * created from today's entities and therefore already has everything those migrations add. A
 * single statement that assumes the old shape fails the whole migration, and the application does
 * not start.
 *
 * <p>Which is exactly what V11 did — it added columns the entity already declares and dropped
 * columns Hibernate never created. Nothing tested the claim, so nothing noticed.
 *
 * <p>Flyway is switched off for the context here so Hibernate builds the schema first; the
 * migrations are then run by hand against it, the way an upgrading deployment would.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ddl-auto-upgrade;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
class DdlAutoToFlywayUpgradeTest {

    @Autowired
    DataSource dataSource;

    @Test
    void everyMigrationRunsOverASchemaHibernateBuilt() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/workflow")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .validateOnMigrate(true)
                .load();

        assertThatNoException().isThrownBy(flyway::migrate);
    }

    /**
     * The other half of making V11 run twice over: on a database Flyway built from V1 the old
     * `status` column is real and holds data, and the flags it becomes must still be carried over.
     * That is the part making the migration idempotent could quietly have broken.
     */
    @Test
    void aDefinitionDisabledUnderTheOldStatusColumnStaysDisabled() throws Exception {
        var empty = new DriverManagerDataSource(
                "jdbc:h2:mem:flyway-carry-over;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        migrate(empty, "10");

        try (var connection = empty.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    insert into workflow_definition_entity (id, name, version, status)
                    values ('wf-disabled', 'Disabled one', 1, 'DISABLED')
                    """);
            statement.execute("""
                    insert into workflow_definition_entity (id, name, version, status)
                    values ('wf-active', 'Active one', 1, 'ACTIVE')
                    """);
        }

        migrate(empty, null);

        try (var connection = empty.getConnection(); var statement = connection.createStatement()) {
            var rows = statement.executeQuery(
                    "select id, disabled, archived from workflow_definition_entity order by id");
            rows.next();
            assertThat(rows.getString("id")).isEqualTo("wf-active");
            assertThat(rows.getBoolean("disabled")).isFalse();
            rows.next();
            assertThat(rows.getString("id")).isEqualTo("wf-disabled");
            assertThat(rows.getBoolean("disabled")).isTrue();
            assertThat(rows.getBoolean("archived")).isFalse();
        }
    }

    /** Migrates up to {@code target}, or all the way when it is null. */
    private void migrate(DriverManagerDataSource dataSource, String target) {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/workflow")
                .baselineOnMigrate(true)
                .baselineVersion("1");
        if (target != null) {
            flyway = flyway.target(MigrationVersion.fromVersion(target));
        }
        flyway.load().migrate();
    }
}
